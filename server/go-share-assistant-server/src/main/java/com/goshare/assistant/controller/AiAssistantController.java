package com.goshare.assistant.controller;

import com.goshare.assistant.dto.SemanticSearchVO;
import com.goshare.assistant.llm.LlmGateway;
import com.goshare.assistant.llm.LlmResult;
import com.goshare.assistant.service.AiSearchService;
import com.goshare.assistant.service.CancelRetentionService;
import com.goshare.assistant.service.DepartureAdvisorService;
import com.goshare.assistant.service.LlmStatsService;
import com.goshare.assistant.service.OrderDraftService;
import com.goshare.assistant.service.WorkOrderService;
import com.goshare.common.result.Result;
import com.goshare.common.security.CurrentUserResolver;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 出行助手接口（A1 语义化搜索 / A2 取消挽留 / B1 工单摘要 / 省token四关卡演示 / 记账统计）
 */
@RestController
@RequestMapping("/api/assistant")
@RequiredArgsConstructor
public class AiAssistantController {

    private final AiSearchService aiSearchService;
    private final CancelRetentionService cancelRetentionService;
    private final DepartureAdvisorService departureAdvisorService;
    private final WorkOrderService workOrderService;
    private final OrderDraftService orderDraftService;
    private final LlmGateway llmGateway;
    private final LlmStatsService llmStatsService;
    private final CurrentUserResolver currentUserResolver;

    /** A1 语义化行程搜索与智能撮合（NL→结构化→ES粗排→特征细排→解释） */
    @PostMapping("/semantic-search")
    public Result<SemanticSearchVO> semanticSearch(@RequestBody @Valid TextReq req) {
        return Result.success(aiSearchService.semanticSearch(req.getText()));
    }

    /** 通用问答（走省 token 四关卡：答案缓存/小模型先答/固定前缀/夜间预生成；支持多轮上下文 history） */
    @PostMapping("/chat")
    public Result<Map<String, Object>> chat(@RequestBody @Valid ChatReq req) {
        LlmResult result = llmGateway.ask("FAQ天气", req.getText(),
                com.goshare.assistant.llm.LlmClient.Tier.SMALL, req.getHistory());
        return Result.success(Map.of(
                "answer", result.getText(),
                "fromCache", result.isFromCache(),
                "modelName", result.getModelName(),
                "gateSkipped", result.isGateSkipped(),
                "inputTokens", result.getInputTokens(),
                "outputTokens", result.getOutputTokens(),
                "durationMs", result.getDurationMs()
        ));
    }

    /** 记账统计：省 token 效果（缓存命中/关卡拦截/估算节省） */
    @GetMapping("/llm-stats")
    public Result<Map<String, Object>> llmStats() {
        return Result.success(llmStatsService.stats());
    }

    /**
     * F1 对话式下单：口语 → 订单草稿确认卡。
     * 全程零模型调用（NlTripParser 规则解析 + trip 真实班次 + 确定性算价），
     * 用户点「确认下单」后前端才调 /order/create，AI 永不碰钱。
     */
    @PostMapping("/order-draft")
    public Result<com.goshare.assistant.dto.OrderDraftVO> orderDraft(@RequestBody @Valid TextReq req) {
        return Result.success(orderDraftService.draft(req.getText()));
    }

    /**
     * F2 出发参谋：订单 → 「几点出门」确定性建议。
     * 发车时间（行程服务）+ 出发地真实天气（notify/Open-Meteo）+ 固定通勤口径合成，
     * 零 LLM 调用，每个数字可追溯。天气拿不到时诚实降级不计缓冲。
     */
    @PostMapping("/departure-advice")
    public Result<Object> departureAdvice(@RequestHeader(value = "Authorization", required = false) String auth,
                                          @RequestBody @Valid OrderNoReq req) {
        Long userId = currentUserResolver.resolveUserId(auth);
        return Result.success(departureAdvisorService.advise(userId, req.getOrderNo()));
    }

    /** A2 取消挽留预览（费用解释 + 替代方案 + 挽留话术，金额由 order FeeCalculator 纯 Java 算） */
    @PostMapping("/cancel-preview")
    public Result<com.goshare.assistant.dto.CancelRetentionVO> cancelPreview(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody @Valid OrderNoReq req) {
        // 带上当前用户，由订单服务校验"只能看自己的订单"
        Long userId = currentUserResolver.resolveUserId(auth);
        return Result.success(cancelRetentionService.preview(userId, req.getOrderNo()));
    }

    /** B1 工单分析：会话摘要 + 回复草稿（摘要只触发一次） */
    @PostMapping("/work-order/analyze")
    public Result<com.goshare.assistant.dto.WorkOrderAnalyzeVO> analyzeWorkOrder(@RequestBody @Valid OrderNoReq req) {
        return Result.success(workOrderService.analyze(req.getOrderNo()));
    }

    /** B1 模拟工单：造会话数据并生成摘要/草稿（演示用） */
    @PostMapping("/work-order/mock")
    public Result<com.goshare.assistant.dto.WorkOrderAnalyzeVO> mockWorkOrder(@RequestBody @Valid MockWorkOrderReq req) {
        return Result.success(workOrderService.createMock(req.getOrderNo(), req.getMessages()));
    }

    /** 运维接口：手动触发夜间预生成热点问答 */
    @PostMapping("/prewarm")
    public Result<Integer> prewarm() {
        int before = llmGateway.HOT_QUESTIONS.size();
        llmGateway.prewarmHotQuestions();
        return Result.success(before);
    }

    @Data
    public static class TextReq {
        @NotBlank(message = "请输入要搜索的内容")
        private String text;
    }

    @Data
    public static class ChatReq {
        @NotBlank(message = "请输入问题内容")
        private String text;
        /** 多轮上下文：[{role: user|assistant, content}]，模型据此理解前文，可为空 */
        private List<Map<String, String>> history;
    }

    @Data
    public static class OrderNoReq {
        @NotBlank(message = "订单号不能为空")
        private String orderNo;
    }

    @Data
    public static class MockWorkOrderReq {
        @NotBlank(message = "订单号不能为空")
        private String orderNo;
        @NotEmpty(message = "工单消息不能为空")
        private List<String> messages;
    }
}
