package com.goshare.assistant.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.goshare.assistant.dto.WorkOrderAnalyzeVO;
import com.goshare.assistant.entity.LlmCallLog;
import com.goshare.assistant.entity.WorkOrder;
import com.goshare.assistant.entity.WorkOrderMessage;
import com.goshare.assistant.mapper.LlmCallLogMapper;
import com.goshare.assistant.mapper.WorkOrderMapper;
import com.goshare.assistant.mapper.WorkOrderMessageMapper;
import com.goshare.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * B1 客服工单智能分派 + 会话摘要 + 回复草稿
 * <p>
 * 设计：
 * 1. 摘要/草稿基于真实会话内容由规则引擎提炼（可靠、可演示），LLM 为可插拔增强（http 模式接真模型）；
 * 2. 「摘要只触发一次」控成本：Redis 幂等键，同一工单只生成一次；
 * 3. 高频标签规则兜底：退款/改期/候补/其他 分类由关键词规则判定；
 * 4. 每次分析写入 llm_call_log 记账（gate_skipped=1 表示未调大模型，体现成本控制）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkOrderService {

    private static final String SUMMARY_KEY_PREFIX = "work-order:sum:";
    private static final Duration SUMMARY_TTL = Duration.ofHours(24);

    /** 分类关键词规则（高频标签兜底） */
    private static final Map<String, List<String>> CATEGORY_RULES = Map.of(
            "退款", List.of("退款", "退钱", "扣钱", "违约金", "钱"),
            "改期", List.of("改期", "改时间", "换班次", "换行程", "晚点"),
            "候补", List.of("候补", "没座位", "没票", "排不上", "满座")
    );

    private final WorkOrderMapper workOrderMapper;
    private final WorkOrderMessageMapper messageMapper;
    private final LlmCallLogMapper llmCallLogMapper;
    private final StringRedisTemplate redisTemplate;

    /**
     * 分析工单：查/建工单 → 会话摘要 + 回复草稿（首次生成，Redis 幂等）
     */
    public WorkOrderAnalyzeVO analyze(String orderNo) {
        WorkOrder order = workOrderMapper.selectOne(
                new LambdaQueryWrapper<WorkOrder>().eq(WorkOrder::getOrderNo, orderNo));
        if (order == null) {
            throw new BusinessException(404, "工单不存在，请先创建模拟工单");
        }
        List<WorkOrderMessage> messages = messageMapper.selectList(
                new LambdaQueryWrapper<WorkOrderMessage>()
                        .eq(WorkOrderMessage::getWorkOrderId, order.getId())
                        .orderByAsc(WorkOrderMessage::getId));

        // 摘要只触发一次（Redis 幂等），防止重复调用模型/重复计算；Redis 不可用时降级为首次生成
        String summaryKey = SUMMARY_KEY_PREFIX + order.getId();
        boolean firstTime = true;
        try {
            firstTime = Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(summaryKey, "1", SUMMARY_TTL));
        } catch (Exception e) {
            log.warn("[work-order] Redis 不可用，跳过摘要幂等: {}", e.getMessage());
        }

        String summary;
        String draft;
        if (firstTime || order.getSummary() == null) {
            // 首次：规则引擎提炼（真实会话内容），并记账（gate_skipped=1 未调大模型）
            summary = buildSummary(messages, order);
            draft = buildDraft(order, summary);
            order.setSummary(summary);
            order.setReplyDraft(draft);
            order.setStatus("PROCESSING");
            workOrderMapper.updateById(order);
            recordSummaryLog(order, summary.length());
        } else {
            summary = order.getSummary();
            draft = order.getReplyDraft();
        }

        WorkOrderAnalyzeVO vo = new WorkOrderAnalyzeVO();
        vo.setWorkOrderId(order.getId());
        vo.setOrderNo(orderNo);
        vo.setCategory(order.getCategory());
        vo.setStatus(order.getStatus());
        vo.setSummary(summary);
        vo.setReplyDraft(draft);
        vo.setMessageCount(messages.size());
        vo.setFirstTime(firstTime);
        return vo;
    }

    /** 创建模拟工单 + 会话（演示数据） */
    public WorkOrderAnalyzeVO createMock(String orderNo, List<String> messages) {
        if (messages == null || messages.isEmpty()) {
            throw new BusinessException(400, "工单消息不能为空");
        }
        WorkOrder order = workOrderMapper.selectOne(
                new LambdaQueryWrapper<WorkOrder>().eq(WorkOrder::getOrderNo, orderNo));
        if (order == null) {
            order = new WorkOrder();
            order.setOrderNo(orderNo);
            order.setCategory(classify(messages));
            order.setStatus("OPEN");
            workOrderMapper.insert(order);
        }
        for (String m : messages) {
            WorkOrderMessage msg = new WorkOrderMessage();
            msg.setWorkOrderId(order.getId());
            msg.setSender("USER");
            msg.setContent(m);
            messageMapper.insert(msg);
        }
        return analyze(orderNo);
    }

    /** 会话摘要：规则提炼真实内容（用户诉求 + 关键信息） */
    private String buildSummary(List<WorkOrderMessage> messages, WorkOrder order) {
        if (messages.isEmpty()) {
            return "该工单暂无会话消息。";
        }
        WorkOrderMessage last = messages.get(messages.size() - 1);
        String latest = last.getContent().length() > 60
                ? last.getContent().substring(0, 60) + "…" : last.getContent();
        return "用户关于「" + order.getCategory() + "」的咨询，共 " + messages.size()
                + " 条消息，最新诉求：" + latest;
    }

    /** 回复草稿：按分类模板生成（含订单号，直接可发） */
    private String buildDraft(WorkOrder order, String summary) {
        return switch (order.getCategory() == null ? "其他" : order.getCategory()) {
            case "退款" -> "您好，关于订单 " + order.getOrderNo() + " 的费用问题：距发车 2 小时外取消免费，"
                    + "2 小时内按实付 20% 收取违约金（封顶 50 元），退款 1-3 个工作日原路返回。需要我为您确认具体金额吗？";
            case "改期" -> "您好，订单 " + order.getOrderNo() + " 暂不支持直接改签。建议先取消原订单（按取消规则），"
                    + "再选择同路线其他时段的行程，我可以帮您找替代方案。";
            case "候补" -> "您好，订单 " + order.getOrderNo() + " 涉及候补：满座行程已为您登记候补，"
                    + "有座位释放会按登记顺序自动补位，候补期间可随时退出，请留意消息提醒。";
            default -> "您好，已收到您关于订单 " + order.getOrderNo() + " 的反馈，我们正在处理，"
                    + "稍后为您回复具体方案。";
        };
    }

    /** 分类：关键词规则兜底 */
    private String classify(List<String> messages) {
        String text = String.join(" ", messages);
        for (Map.Entry<String, List<String>> rule : CATEGORY_RULES.entrySet()) {
            for (String kw : rule.getValue()) {
                if (text.contains(kw)) {
                    return rule.getKey();
                }
            }
        }
        return "其他";
    }

    /** 记账：B1 摘要生成（未调大模型，gate_skipped=1） */
    private void recordSummaryLog(WorkOrder order, int outputLen) {
        try {
            LlmCallLog logRow = new LlmCallLog();
            logRow.setBizType("B1工单摘要");
            logRow.setModelName("小模型(规则)");
            logRow.setPromptHash("work-order:" + order.getId());
            logRow.setInputTokens(0);
            logRow.setOutputTokens(outputLen);
            logRow.setDurationMs(0);
            logRow.setCacheHit(0);
            logRow.setGateSkipped(1);
            llmCallLogMapper.insert(logRow);
        } catch (Exception e) {
            log.warn("[b1] 记账失败: {}", e.getMessage());
        }
    }
}
