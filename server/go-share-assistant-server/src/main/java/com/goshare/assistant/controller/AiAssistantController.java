package com.goshare.assistant.controller;

import com.goshare.assistant.dto.SemanticSearchVO;
import com.goshare.assistant.llm.LlmGateway;
import com.goshare.assistant.llm.LlmResult;
import com.goshare.assistant.service.AiSearchService;
import com.goshare.assistant.service.CancelRetentionService;
import com.goshare.assistant.service.LlmStatsService;
import com.goshare.assistant.service.WorkOrderService;
import com.goshare.common.result.Result;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final WorkOrderService workOrderService;
    private final LlmGateway llmGateway;
    private final LlmStatsService llmStatsService;

    /** A1 语义化行程搜索与智能撮合（NL→结构化→ES粗排→特征细排→解释） */
    @PostMapping("/semantic-search")
    public Result<SemanticSearchVO> semanticSearch(@RequestBody TextReq req) {
        return Result.success(aiSearchService.semanticSearch(req.getText()));
    }

    /** 通用问答（走省 token 四关卡：答案缓存/小模型先答/固定前缀/夜间预生成） */
    @PostMapping("/chat")
    public Result<Map<String, Object>> chat(@RequestBody TextReq req) {
        LlmResult result = llmGateway.ask("FAQ天气", req.getText(), com.goshare.assistant.llm.LlmClient.Tier.SMALL);
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

    /** A2 取消挽留预览（费用解释 + 替代方案 + 挽留话术，金额由 order FeeCalculator 纯 Java 算） */
    @PostMapping("/cancel-preview")
    public Result<com.goshare.assistant.dto.CancelRetentionVO> cancelPreview(@RequestBody OrderNoReq req) {
        return Result.success(cancelRetentionService.preview(req.getOrderNo()));
    }

    /** B1 工单分析：会话摘要 + 回复草稿（摘要只触发一次） */
    @PostMapping("/work-order/analyze")
    public Result<com.goshare.assistant.dto.WorkOrderAnalyzeVO> analyzeWorkOrder(@RequestBody OrderNoReq req) {
        return Result.success(workOrderService.analyze(req.getOrderNo()));
    }

    /** B1 模拟工单：造会话数据并生成摘要/草稿（演示用） */
    @PostMapping("/work-order/mock")
    public Result<com.goshare.assistant.dto.WorkOrderAnalyzeVO> mockWorkOrder(@RequestBody MockWorkOrderReq req) {
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
        @NotBlank
        private String text;
    }

    @Data
    public static class OrderNoReq {
        @NotBlank
        private String orderNo;
    }

    @Data
    public static class MockWorkOrderReq {
        @NotBlank
        private String orderNo;
        private List<String> messages;
    }
}
