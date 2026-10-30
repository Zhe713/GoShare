package com.goshare.assistant.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.goshare.assistant.entity.LlmCallLog;
import com.goshare.assistant.mapper.LlmCallLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM 记账统计：演示省 token 四关卡的效果（缓存命中率 / 关卡拦截率 / 估算节省成本）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmStatsService {

    private final LlmCallLogMapper llmCallLogMapper;

    public Map<String, Object> stats() {
        List<LlmCallLog> logs = llmCallLogMapper.selectList(
                new LambdaQueryWrapper<LlmCallLog>().orderByDesc(LlmCallLog::getId).last("LIMIT 500"));
        long total = logs.size();
        long cacheHit = logs.stream().filter(l -> l.getCacheHit() != null && l.getCacheHit() == 1).count();
        long gateSkipped = logs.stream().filter(l -> l.getGateSkipped() != null && l.getGateSkipped() == 1).count();
        long modelCalled = total - cacheHit - gateSkipped;
        long totalInput = logs.stream().mapToLong(l -> l.getInputTokens() == null ? 0 : l.getInputTokens()).sum();
        long totalOutput = logs.stream().mapToLong(l -> l.getOutputTokens() == null ? 0 : l.getOutputTokens()).sum();
        // 估算：若全部走大模型（按 5 元/百万 token 计价），与当前策略的成本差
        double allModelCost = (totalInput + totalOutput) / 1_000_000.0 * 5.0;
        double actualCost = modelCalled / 1_000_000.0 * 5.0;
        double saved = allModelCost - actualCost;

        Map<String, Object> result = new HashMap<>();
        result.put("totalRequests", total);
        result.put("cacheHitCount", cacheHit);
        result.put("gateSkippedCount", gateSkipped);
        result.put("modelCalledCount", modelCalled);
        result.put("cacheHitRate", total == 0 ? 0 : Math.round(cacheHit * 1000.0 / total) / 10.0);
        result.put("gateSkippedRate", total == 0 ? 0 : Math.round(gateSkipped * 1000.0 / total) / 10.0);
        result.put("estimatedTokensSaved", totalInput + totalOutput);
        result.put("estimatedCostSavedYuan", Math.round(saved * 100.0) / 100.0);
        return result;
    }
}
