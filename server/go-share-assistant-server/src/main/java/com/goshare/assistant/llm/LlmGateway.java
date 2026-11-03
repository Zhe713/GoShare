package com.goshare.assistant.llm;

import com.goshare.assistant.config.LlmProperties;
import com.goshare.assistant.entity.LlmCallLog;
import com.goshare.assistant.mapper.LlmCallLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * LLM 统一网关：省 token 四关卡 + 全量记账
 * <p>
 * 关卡1「答案缓存」：同前缀+同问题命中 Redis 直接返回（缓存键带数据版本号，价格/余座变化即作废）；
 * 关卡2「小模型先答」：固定前缀意图（通用规则类）由规则模板零成本作答，不调大模型；
 * 关卡3「固定前缀」：所有 prompt 强制以系统前缀开头，保证厂商侧缓存复用 + 防注入；
 * 关卡4「夜间预生成」：凌晨批量预生成热点问答写入缓存，白天命中即零延迟零调用。
 * <p>
 * 每次处理都写入 llm_call_log 记账（模型名/输入输出 token/耗时/是否缓存命中/是否关卡拦截），
 * 供演示「成本 A/B 对比」与简历量化。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmGateway {

    /** 固定系统前缀：所有 prompt 以此开头（厂商缓存复用 + 防注入） */
    public static final String SYSTEM_PREFIX = "你是 GoShare 顺风拼车平台的出行助手。GoShare 是演示用顺风拼车平台，示例运营线路以中国河北省及周边城市之间的城际拼车为主（如石家庄、邯郸、保定、北京等城市）。回答用户出行问题时：1）先说明平台以这些示例城市运营；2）引导用户提供出发地、目的地与出行日期，以便查询系统内真实可订线路；3）绝不编造平台未覆盖的运营区域、公司背景、具体价格、班次时间或政策条款；4）凡系统数据无法确定的业务信息，一律提示用户查询具体线路，不自行脑补。回复风格要求：像真人客服一样简洁口语化，一次回复不超过 4 句话；不使用 emoji、感叹号堆砌、营销腔和“重磅/超值/震撼”等话术；不知道就说不知道，不硬凑。";

    /** 答案缓存 TTL：12 小时（与夜间预生成周期匹配，每晚重生成） */
    private static final Duration CACHE_TTL = Duration.ofHours(12);

    private final LlmClient llmClient;
    private final StringRedisTemplate redisTemplate;
    private final LlmCallLogMapper llmCallLogMapper;
    private final LlmProperties properties;
    private final WeatherRuleProvider weatherRuleProvider;

    /**
     * 统一入口
     *
     * @param bizType  业务类型：A1语义搜索 / B1工单摘要 / A2取消挽留 / FAQ天气
     * @param question 用户问题（原始表述，无需带前缀）
     * @param tier     模型档位（规则意图走 SMALL，生成类走 LARGE）
     */
    public LlmResult ask(String bizType, String question, LlmClient.Tier tier) {
        long start = System.currentTimeMillis();
        // 关卡3：固定前缀（强制拼接，保证厂商缓存 key 稳定）
        String prompt = SYSTEM_PREFIX + "\n用户：" + question;
        String hash = DigestUtils.md5DigestAsHex(prompt.getBytes(StandardCharsets.UTF_8));
        String cacheKey = cacheKey(hash);

        // 关卡1：答案缓存（带数据版本号，版本 +1 后旧答案自动作废；Redis 不可用时降级跳过，不影响主流程）
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                record(bizType, tier, hash, 0, 0, (int) (System.currentTimeMillis() - start), true, false);
                log.info("[llm-gateway] 关卡1缓存命中 biz={} q={}", bizType, question);
                return LlmResult.of(cached, true, "cache", false, 0, 0, (int) (System.currentTimeMillis() - start));
            }
        } catch (Exception e) {
            log.warn("[llm-gateway] Redis 不可用，跳过缓存关卡: {}", e.getMessage());
        }

        // 关卡2：天气意图（真实数据·零模型调用，来自 notify 天气服务）
        String weatherAnswer = weatherRuleProvider.maybeAnswer(question);
        if (weatherAnswer != null) {
            try {
                redisTemplate.opsForValue().set(cacheKey, weatherAnswer, CACHE_TTL);
            } catch (Exception e) {
                log.warn("[llm-gateway] 天气答案写缓存失败（不影响返回）: {}", e.getMessage());
            }
            record(bizType, tier, hash, 0, 0, (int) (System.currentTimeMillis() - start), false, true);
            log.info("[llm-gateway] 关卡2天气数据命中 biz={} q={}", bizType, question);
            return LlmResult.of(weatherAnswer, false, "小模型(天气)", true, 0, 0, (int) (System.currentTimeMillis() - start));
        }

        // 关卡2：小模型先答（规则意图零调用，未触碰大模型）
        String ruleAnswer = RuleAnswerProvider.maybeAnswer(question);
        if (ruleAnswer != null) {
            try {
                redisTemplate.opsForValue().set(cacheKey, ruleAnswer, CACHE_TTL);
            } catch (Exception e) {
                log.warn("[llm-gateway] 规则答案写缓存失败（不影响返回）: {}", e.getMessage());
            }
            record(bizType, tier, hash, 0, 0, (int) (System.currentTimeMillis() - start), false, true);
            log.info("[llm-gateway] 关卡2小模型规则命中 biz={} q={}", bizType, question);
            return LlmResult.of(ruleAnswer, false, "小模型(规则)", true, 0, 0, (int) (System.currentTimeMillis() - start));
        }

        // 调模型（大模型档，mock 零成本 / http 真实 API）
        LlmClient.LlmResponse resp = llmClient.call(prompt, tier);
        try {
            redisTemplate.opsForValue().set(cacheKey, resp.text(), CACHE_TTL);
        } catch (Exception e) {
            log.warn("[llm-gateway] 答案写缓存失败（不影响返回）: {}", e.getMessage());
        }
        int elapsed = (int) (System.currentTimeMillis() - start);
        record(bizType, tier, hash, resp.inputTokens(), resp.outputTokens(), elapsed, false, false);
        log.info("[llm-gateway] 调用模型 biz={} q={} model={} in={} out={}",
                bizType, question, tier, resp.inputTokens(), resp.outputTokens());
        return LlmResult.of(resp.text(), false, tier == LlmClient.Tier.SMALL ? "小模型" : "大模型",
                false, resp.inputTokens(), resp.outputTokens(), elapsed);
    }

    /** 关卡4：夜间预生成热点问答（凌晨 3 点执行；也可通过运维接口手动触发） */
    @Scheduled(cron = "0 0 3 * * ?")
    public void prewarmHotQuestions() {
        log.info("[llm-gateway] 夜间预生成开始，数据版本={}", properties.getDataVersion());
        for (String q : HOT_QUESTIONS) {
            try {
                ask("FAQ天气", q, LlmClient.Tier.SMALL);
            } catch (Exception e) {
                log.warn("[llm-gateway] 预生成失败 q={} err={}", q, e.getMessage());
            }
        }
        log.info("[llm-gateway] 夜间预生成完成，共 {} 条", HOT_QUESTIONS.size());
    }

    /** 热点问答（预生成/演示用） */
    public static final List<String> HOT_QUESTIONS = List.of(
            "取消订单会扣钱吗？",
            "迟到了还能上车吗？",
            "候补排队多久能排到？",
            "用券之后取消券会退吗？",
            "行程什么时候开始发车提醒？"
    );

    /** 缓存键：数据版本号 + 前缀问题哈希（版本号变化自动 miss） */
    public String cacheKey(String promptHash) {
        return "ai:ans:v" + properties.getDataVersion() + ":" + promptHash;
    }

    private void record(String bizType, LlmClient.Tier tier, String hash,
                        int in, int out, int durationMs, boolean cacheHit, boolean gateSkipped) {
        try {
            LlmCallLog logRow = new LlmCallLog();
            logRow.setBizType(bizType);
            logRow.setModelName(cacheHit ? "cache" : gateSkipped ? "小模型(规则)" : tier == LlmClient.Tier.SMALL ? "小模型" : "大模型");
            logRow.setPromptHash(hash);
            logRow.setInputTokens(in);
            logRow.setOutputTokens(out);
            logRow.setDurationMs(durationMs);
            logRow.setCacheHit(cacheHit ? 1 : 0);
            logRow.setGateSkipped(gateSkipped ? 1 : 0);
            llmCallLogMapper.insert(logRow);
        } catch (Exception e) {
            log.warn("[llm-gateway] 记账失败（不影响主流程）: {}", e.getMessage());
        }
    }
}
