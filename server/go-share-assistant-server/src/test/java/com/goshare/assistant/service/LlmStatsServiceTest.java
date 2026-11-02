package com.goshare.assistant.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.goshare.assistant.entity.LlmCallLog;
import com.goshare.assistant.mapper.LlmCallLogMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * LLM 记账统计：缓存命中率/规则拦截率/模型直调数/成本估算口径
 */
class LlmStatsServiceTest {

    private LlmCallLogMapper mapper;
    private LlmStatsService service;

    private LlmCallLog log(int cacheHit, int gateSkipped, int in, int out) {
        LlmCallLog l = new LlmCallLog();
        l.setCacheHit(cacheHit);
        l.setGateSkipped(gateSkipped);
        l.setInputTokens(in);
        l.setOutputTokens(out);
        return l;
    }

    @Test
    void statsComputesRatesAndCounts() {
        mapper = mock(LlmCallLogMapper.class);
        service = new LlmStatsService(mapper);
        // 10 条：4 缓存命中 + 3 规则拦截 + 3 直调模型
        List<LlmCallLog> logs = List.of(
                log(1, 0, 0, 0), log(1, 0, 0, 0), log(1, 0, 0, 0), log(1, 0, 0, 0),
                log(0, 1, 0, 0), log(0, 1, 0, 0), log(0, 1, 0, 0),
                log(0, 0, 100, 50), log(0, 0, 200, 60), log(0, 0, 150, 40)
        );
        when(mapper.selectList(any(Wrapper.class))).thenReturn(logs);

        Map<String, Object> stats = service.stats();

        assertEquals(10L, stats.get("totalRequests"));
        assertEquals(4L, stats.get("cacheHitCount"));
        assertEquals(3L, stats.get("gateSkippedCount"));
        assertEquals(3L, stats.get("modelCalledCount"));
        assertEquals(40.0, stats.get("cacheHitRate"));
        assertEquals(30.0, stats.get("gateSkippedRate"));
        // 直调模型 token 合计：100+50 + 200+60 + 150+40 = 600
        assertEquals(600L, stats.get("estimatedTokensSaved"));
        assertNotNull(stats.get("estimatedCostSavedYuan"));
    }

    @Test
    void statsEmptyReturnsZeroRates() {
        mapper = mock(LlmCallLogMapper.class);
        service = new LlmStatsService(mapper);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        Map<String, Object> stats = service.stats();

        assertEquals(0L, stats.get("totalRequests"));
        assertEquals(0.0, stats.get("cacheHitRate"));
        assertEquals(0.0, stats.get("gateSkippedRate"));
    }

    @Test
    void statsAllCacheHit() {
        mapper = mock(LlmCallLogMapper.class);
        service = new LlmStatsService(mapper);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(log(1, 0, 0, 0), log(1, 0, 0, 0)));

        Map<String, Object> stats = service.stats();

        assertEquals(100.0, stats.get("cacheHitRate"));
        assertEquals(0L, stats.get("modelCalledCount"));
    }
}
