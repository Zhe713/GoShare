package com.goshare.assistant.llm;

import com.goshare.assistant.config.LlmProperties;
import com.goshare.assistant.entity.LlmCallLog;
import com.goshare.assistant.llm.tool.ToolRegistry;
import com.goshare.assistant.mapper.LlmCallLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    /** 工具绑定约束：模型只能基于工具返回的真实数据作答，无工具不答（结构性去幻觉核心） */
    public static final String TOOL_BINDING_RULE = "涉及行程班次、天气、农历、当前时间等需要实时数据的问题，你必须先调用提供的工具获取真实数据后再回答，禁止编造或凭记忆猜测数据。若没有可用工具或工具返回为空，必须明确告知用户“我暂时查不到这项数据”，绝不自行编造。";

    /** 工具调用最大轮数（防模型死循环；每轮至少执行一次工具取数） */
    private static final int MAX_TOOL_ROUNDS = 3;

    /** 答案缓存 TTL：12 小时（与夜间预生成周期匹配，每晚重生成）；天气答案实时性要求高，单独 15 分钟 */
    private static final Duration CACHE_TTL = Duration.ofHours(12);

    /** 天气答案缓存 TTL：15 分钟（保证天气实时更新，不把旧天气当新天气答出去） */
    private static final Duration WEATHER_CACHE_TTL = Duration.ofMinutes(15);

    /** 星期中文 */
    private static final String[] WEEK_CN = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    private final LlmClient llmClient;
    private final StringRedisTemplate redisTemplate;
    private final LlmCallLogMapper llmCallLogMapper;
    private final LlmProperties properties;
    private final WeatherRuleProvider weatherRuleProvider;
    private final TripRuleProvider tripRuleProvider;
    private final TimeRuleProvider timeRuleProvider;
    private final ToolRegistry toolRegistry;
    private final LunarRuleProvider lunarRuleProvider;

    /**
     * 统一入口（无历史上下文的便捷重载）
     *
     * @param bizType  业务类型：A1语义搜索 / B1工单摘要 / A2取消挽留 / FAQ天气
     * @param question 用户问题（原始表述，无需带前缀）
     * @param tier     模型档位（规则意图走 SMALL，生成类走 LARGE）
     */
    public LlmResult ask(String bizType, String question, LlmClient.Tier tier) {
        return ask(bizType, question, tier, List.of());
    }

    /**
     * 统一入口（带多轮上下文）
     *
     * @param history 历史对话 [{role: user|assistant, content}]，用于上下文理解；可为空
     */
    public LlmResult ask(String bizType, String question, LlmClient.Tier tier, List<Map<String, String>> history) {
        long start = System.currentTimeMillis();
        // 关卡3：固定前缀（强制拼接，保证厂商缓存 key 稳定）+ 今日日期 + 多轮上下文
        // 日期写入 prompt：模型能感知「今天几号/星期几」；历史拼入后上下文连贯，不再重复要出发地/日期
        StringBuilder prompt = new StringBuilder(todayLine()).append(SYSTEM_PREFIX);
        if (history != null) {
            for (Map<String, String> h : history) {
                String role = h.getOrDefault("role", "user");
                String content = h.getOrDefault("content", "");
                if (content == null || content.isBlank()) {
                    continue;
                }
                prompt.append('\n').append("user".equals(role) ? "用户：" : "助手：").append(content);
            }
        }
        prompt.append("\n用户：").append(question);
        String fullPrompt = prompt.toString();

        // 双缓存：LLM 答案按「日期+前缀+历史+问题」缓存（上下文不同不串答案）；
        // 规则答案与上下文无关，按「日期+前缀+问题」缓存 → 任何会话里重复问同一 FAQ 都命中关卡1，零模型调用；
        // 天气/行程答案按「日期+前缀+意图+城市+日期」缓存 → 同城同日重复问命中，跨会话不串答案
        String contextHash = DigestUtils.md5DigestAsHex(fullPrompt.getBytes(StandardCharsets.UTF_8));
        String rulePrompt = todayLine() + SYSTEM_PREFIX + "\n用户：" + question;
        String ruleHash = DigestUtils.md5DigestAsHex(rulePrompt.getBytes(StandardCharsets.UTF_8));
        String contextKey = cacheKey(contextHash);
        String ruleKey = cacheKey(ruleHash);

        // 意图解析（纯本地字符串处理，不触发外部调用）：天气/行程先解析出稳定缓存键，避免命中别的会话的答案
        WeatherRuleProvider.WeatherQuery wq = weatherRuleProvider.resolve(question, history);
        String weatherKey = wq != null
                ? cacheKey(DigestUtils.md5DigestAsHex((todayLine() + SYSTEM_PREFIX
                + "#weather#" + wq.city + "#" + wq.date).getBytes(StandardCharsets.UTF_8)))
                : null;
        TripRuleProvider.TripQuery tq = tripRuleProvider.resolve(question, history);
        String tripKey = tq != null && "fetch".equals(tq.mode)
                ? cacheKey(DigestUtils.md5DigestAsHex((todayLine() + SYSTEM_PREFIX
                + "#trip#" + tq.from + "#" + tq.to + "#" + tq.date).getBytes(StandardCharsets.UTF_8)))
                : null;

        // 关卡1：答案缓存（带数据版本号，版本 +1 后旧答案自动作废；Redis 不可用时降级跳过，不影响主流程）
        try {
            String cached = redisTemplate.opsForValue().get(contextKey);
            if (cached == null) {
                cached = redisTemplate.opsForValue().get(ruleKey);
            }
            if (cached == null && weatherKey != null) {
                cached = redisTemplate.opsForValue().get(weatherKey);
            }
            if (cached == null && tripKey != null) {
                cached = redisTemplate.opsForValue().get(tripKey);
            }
            if (cached != null) {
                record(bizType, tier, contextHash, 0, 0, (int) (System.currentTimeMillis() - start), true, false);
                log.info("[llm-gateway] 关卡1缓存命中 biz={} q={}", bizType, question);
                return LlmResult.of(cached, true, "cache", false, 0, 0, (int) (System.currentTimeMillis() - start));
            }
        } catch (Exception e) {
            log.warn("[llm-gateway] Redis 不可用，跳过缓存关卡: {}", e.getMessage());
        }

        // 关卡2：天气意图（真实数据·零模型调用，来自 notify 天气服务；缓存仅 15 分钟保证实时）
        if (wq != null) {
            String weatherAnswer = weatherRuleProvider.fetch(wq);
            if (weatherAnswer != null) {
                try {
                    redisTemplate.opsForValue().set(weatherKey, weatherAnswer, WEATHER_CACHE_TTL);
                } catch (Exception e) {
                    log.warn("[llm-gateway] 天气答案写缓存失败（不影响返回）: {}", e.getMessage());
                }
                record(bizType, tier, contextHash, 0, 0, (int) (System.currentTimeMillis() - start), false, true);
                log.info("[llm-gateway] 关卡2天气数据命中 biz={} q={} city={} date={}", bizType, question, wq.city, wq.date);
                return LlmResult.of(weatherAnswer, false, "小模型(天气)", true, 0, 0, (int) (System.currentTimeMillis() - start));
            }
        }

        // 关卡2：行程意图（真实班次·零模型调用，杜绝幻觉班次；信息不全时诚实反问不缓存）
        if (tq != null) {
            if ("clarify".equals(tq.mode) || "ask".equals(tq.mode)) {
                record(bizType, tier, contextHash, 0, 0, (int) (System.currentTimeMillis() - start), false, true);
                return LlmResult.of(tq.hint, false, "小模型(行程)", true, 0, 0, (int) (System.currentTimeMillis() - start));
            }
            String tripAnswer = tripRuleProvider.fetch(tq);
            if (tripAnswer != null) {
                try {
                    redisTemplate.opsForValue().set(tripKey, tripAnswer, CACHE_TTL);
                } catch (Exception e) {
                    log.warn("[llm-gateway] 行程答案写缓存失败（不影响返回）: {}", e.getMessage());
                }
                record(bizType, tier, contextHash, 0, 0, (int) (System.currentTimeMillis() - start), false, true);
                log.info("[llm-gateway] 关卡2行程数据命中 biz={} q={} from={} to={} date={}", bizType, question, tq.from, tq.to, tq.date);
                return LlmResult.of(tripAnswer, false, "小模型(行程)", true, 0, 0, (int) (System.currentTimeMillis() - start));
            }
        }

        // 关卡2：实时时间（零模型调用；时间会变，不写缓存）
        String timeAnswer = timeRuleProvider.maybeAnswer(question);
        if (timeAnswer != null) {
            record(bizType, tier, contextHash, 0, 0, (int) (System.currentTimeMillis() - start), false, true);
            log.info("[llm-gateway] 关卡2时间命中 biz={} q={}", bizType, question);
            return LlmResult.of(timeAnswer, false, "小模型(时间)", true, 0, 0, (int) (System.currentTimeMillis() - start));
        }

        // 关卡2：农历（零模型调用·离线确定性历法；农历日期可复现，写入缓存）
        String lunarAnswer = lunarRuleProvider.maybeAnswer(question);
        if (lunarAnswer != null) {
            try {
                redisTemplate.opsForValue().set(ruleKey, lunarAnswer, CACHE_TTL);
            } catch (Exception e) {
                log.warn("[llm-gateway] 农历答案写缓存失败（不影响返回）: {}", e.getMessage());
            }
            record(bizType, tier, ruleHash, 0, 0, (int) (System.currentTimeMillis() - start), false, true);
            log.info("[llm-gateway] 关卡2农历命中 biz={} q={}", bizType, question);
            return LlmResult.of(lunarAnswer, false, "小模型(农历)", true, 0, 0, (int) (System.currentTimeMillis() - start));
        }

        // 关卡2：小模型先答（规则意图零调用，未触碰大模型）
        String ruleAnswer = RuleAnswerProvider.maybeAnswer(question);
        if (ruleAnswer != null) {
            try {
                redisTemplate.opsForValue().set(ruleKey, ruleAnswer, CACHE_TTL);
            } catch (Exception e) {
                log.warn("[llm-gateway] 规则答案写缓存失败（不影响返回）: {}", e.getMessage());
            }
            record(bizType, tier, ruleHash, 0, 0, (int) (System.currentTimeMillis() - start), false, true);
            log.info("[llm-gateway] 关卡2小模型规则命中 biz={} q={}", bizType, question);
            return LlmResult.of(ruleAnswer, false, "小模型(规则)", true, 0, 0, (int) (System.currentTimeMillis() - start));
        }

        // 关卡3.5：Function Calling 工具通道（结构性去幻觉兜底）——
        // 规则未命中时不再裸生成：模型只能通过工具取真实数据（行程/天气/农历/时间），
        // 工具执行结果回填后再让模型组织话术；无工具可答时模型按约束明确说「查不到」，绝不编造。
        List<LlmClient.ToolMessage> messages = new ArrayList<>();
        messages.add(new LlmClient.ToolMessage("system", todayLine() + SYSTEM_PREFIX + TOOL_BINDING_RULE));
        if (history != null) {
            for (Map<String, String> h : history) {
                String role = h.getOrDefault("role", "user");
                String content = h.getOrDefault("content", "");
                if (!"user".equals(role) && !"assistant".equals(role)) {
                    continue;
                }
                if (content == null || content.isBlank()) {
                    continue;
                }
                messages.add(new LlmClient.ToolMessage(role, content));
            }
        }
        messages.add(new LlmClient.ToolMessage("user", question));
        List<Map<String, Object>> tools = toolRegistry.toOpenAiTools();
        LlmClient.ToolChatResponse resp = null;
        int toolRounds = 0;
        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            resp = llmClient.chat(messages, tools, tier);
            if (resp.toolCalls().isEmpty()) {
                break;
            }
            toolRounds++;
            log.info("[llm-gateway] 工具轮次{} 调用{}个工具 biz={} q={}", toolRounds, resp.toolCalls().size(), bizType, question);
            messages.add(LlmClient.ToolMessage.assistantToolCalls(resp.toolCalls()));
            for (LlmClient.ToolCall call : resp.toolCalls()) {
                String result = toolRegistry.execute(call.name(), call.arguments());
                messages.add(LlmClient.ToolMessage.toolResult(call.id(), result));
            }
        }
        String answer = (resp == null || resp.text() == null || resp.text().isBlank())
                ? "抱歉，暂时无法查询到这项数据，请换个问法试试。"
                : resp.text();
        try {
            redisTemplate.opsForValue().set(contextKey, answer, CACHE_TTL);
        } catch (Exception e) {
            log.warn("[llm-gateway] 答案写缓存失败（不影响返回）: {}", e.getMessage());
        }
        int elapsed = (int) (System.currentTimeMillis() - start);
        record(bizType, tier, contextHash, resp == null ? 0 : resp.inputTokens(),
                resp == null ? 0 : resp.outputTokens(), elapsed, false, false);
        log.info("[llm-gateway] 工具通道完成 biz={} q={} model={} tools={} in={} out={}",
                bizType, question, tier, toolRounds,
                resp == null ? 0 : resp.inputTokens(), resp == null ? 0 : resp.outputTokens());
        return LlmResult.of(answer, false, tier == LlmClient.Tier.SMALL ? "小模型" : "大模型",
                false, resp == null ? 0 : resp.inputTokens(), resp == null ? 0 : resp.outputTokens(), elapsed);
    }

    /** 今日日期行：让模型感知「今天几号、星期几」（如「今天是2026年11月3日，周二。」） */
    private String todayLine() {
        LocalDate d = LocalDate.now();
        DayOfWeek dow = d.getDayOfWeek();
        return "今天是" + d.getYear() + "年" + d.getMonthValue() + "月" + d.getDayOfMonth()
                + "日，" + WEEK_CN[dow.getValue() - 1] + "。";
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
