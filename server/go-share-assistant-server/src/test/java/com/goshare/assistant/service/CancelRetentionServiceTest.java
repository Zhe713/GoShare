package com.goshare.assistant.service;

import com.goshare.assistant.dto.CancelRetentionVO;
import com.goshare.assistant.dto.OrderDetailVO;
import com.goshare.assistant.dto.TripSimpleVO;
import com.goshare.assistant.entity.LlmCallLog;
import com.goshare.assistant.llm.LlmClient;
import com.goshare.assistant.llm.LlmGateway;
import com.goshare.assistant.mapper.LlmCallLogMapper;
import com.goshare.common.result.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A2 取消挽留：费用解释回显 / 替代方案过滤（排除当前行程+仅未来）/ 挽留话术 LLM / Redis 幂等
 */
class CancelRetentionServiceTest {

    private RestTemplate restTemplate;
    private StringRedisTemplate redisTemplate;
    private LlmGateway llmGateway;
    private LlmClient llmClient;
    private LlmCallLogMapper llmCallLogMapper;
    private CancelRetentionService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        redisTemplate = mock(StringRedisTemplate.class);
        llmGateway = mock(LlmGateway.class);
        llmClient = mock(LlmClient.class);
        llmCallLogMapper = mock(LlmCallLogMapper.class);
        service = new CancelRetentionService(restTemplate, redisTemplate, llmGateway, llmClient, llmCallLogMapper);
        ReflectionTestUtils.setField(service, "orderUrl", "http://order:8003");
        ReflectionTestUtils.setField(service, "tripUrl", "http://trip:8002");
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);
    }

    private TripSimpleVO trip(Long id, String from, String to, LocalDateTime dep, int remain) {
        TripSimpleVO t = new TripSimpleVO();
        t.setId(id);
        t.setFromCity(from);
        t.setToCity(to);
        t.setDepartureTime(dep);
        t.setPrice(new BigDecimal("40"));
        t.setRemainSeats(remain);
        return t;
    }

    @Test
    @SuppressWarnings("unchecked")
    void previewBuildsFeeAlternativesAndPitch() {
        // 1. 费用明细接口
        when(restTemplate.exchange(contains("/api/order/cancel-preview"), any(), any(),
                any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(Result.success(Map.of(
                        "orderNo", "20261030151932907564",
                        "status", "CREATED",
                        "totalPrice", "40",
                        "couponDiscount", "0",
                        "payPrice", "40",
                        "liability", "无责",
                        "penalty", "0",
                        "refundAmount", "40",
                        "feeExplain", "订单未支付，取消无扣费",
                        "ruleExplain", "距发车 2 小时外取消免费"))));
        // 2. 订单详情接口
        OrderDetailVO orderDetail = new OrderDetailVO();
        orderDetail.setTripId(5L);
        when(restTemplate.exchange(contains("/api/order/20261030151932907564"), any(), any(),
                any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(Result.success(orderDetail)));
        // 3. 行程详情接口
        when(restTemplate.exchange(contains("/api/trip/5"), any(), any(),
                any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(Result.success(trip(5L, "石家庄", "邯郸",
                        LocalDateTime.now().plusDays(1), 3))));
        // 4. 同路线搜索接口（包含当前行程 5 + 一个未来替代 6 + 一个已出发 7）
        when(restTemplate.exchange(contains("/api/trip/search"), any(), any(),
                any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(Result.success(List.of(
                        trip(5L, "石家庄", "邯郸", LocalDateTime.now().plusDays(1), 3),
                        trip(6L, "石家庄", "邯郸", LocalDateTime.now().plusDays(1).plusHours(1), 4),
                        trip(7L, "石家庄", "邯郸", LocalDateTime.now().minusHours(1), 2)
                ))));
        when(llmClient.call(anyString(), eq(LlmClient.Tier.LARGE)))
                .thenReturn(new LlmClient.LlmResponse("别急着取消，现在退没有扣费，还有更合适的时间可选。", 80, 40));

        CancelRetentionVO vo = service.preview("20261030151932907564");

        assertFalse(vo.isAlreadyRetained());
        assertEquals("CREATED", vo.getFee().getStatus());
        assertEquals("无责", vo.getFee().getLiability());
        // 替代方案：排除当前行程 5 + 排除已出发的 7，只留 6
        assertEquals(1, vo.getAlternatives().size());
        assertEquals(6L, vo.getAlternatives().get(0).getTripId());
        // 话术由 LLM 生成且记账
        assertEquals("别急着取消，现在退没有扣费，还有更合适的时间可选。", vo.getPitch());
        ArgumentCaptor<LlmCallLog> captor = ArgumentCaptor.forClass(LlmCallLog.class);
        verify(llmCallLogMapper).insert(captor.capture());
        assertEquals("A2取消挽留", captor.getValue().getBizType());
        // 话术 prompt 强制带系统前缀（金额数据由代码注入）
        verify(llmClient).call(argThat(p -> p.startsWith(LlmGateway.SYSTEM_PREFIX)
                && p.contains("20261030151932907564")), eq(LlmClient.Tier.LARGE));
    }

    @Test
    void previewMarksAlreadyRetainedOnSecondCall() {
        when(redisTemplate.opsForValue().setIfAbsent(anyString(), anyString(), any())).thenReturn(false);
        when(restTemplate.exchange(contains("/api/order/cancel-preview"), any(), any(),
                any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(Result.success(Map.of())));
        when(restTemplate.exchange(contains("/api/order/20261030151932907564"), any(), any(),
                any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(Result.success(null)));
        when(llmClient.call(anyString(), any())).thenReturn(new LlmClient.LlmResponse("话术", 1, 1));

        CancelRetentionVO vo = service.preview("20261030151932907564");

        assertTrue(vo.isAlreadyRetained());
        // 第二次调用 Redis setIfAbsent 仍只发生一次（同一键）
        verify(redisTemplate.opsForValue()).setIfAbsent(eq("ai:cancel-retention:20261030151932907564"),
                eq("1"), eq(Duration.ofHours(24)));
    }

    @Test
    void previewDegradesWhenServicesDown() {
        // order/trip 服务全部不可用：费用为空、替代方案为空，话术仍由 LLM 兜底生成
        when(restTemplate.exchange(anyString(), any(), any(), any(ParameterizedTypeReference.class)))
                .thenThrow(new RuntimeException("order down"));
        when(llmClient.call(anyString(), any())).thenReturn(new LlmClient.LlmResponse("请再考虑一下，现在退没有额外损失。", 60, 30));

        CancelRetentionVO vo = service.preview("20261030151932907564");

        assertNull(vo.getFee());
        assertTrue(vo.getAlternatives().isEmpty());
        assertTrue(vo.getPitch().contains("请再考虑一下"));
        // 兜底话术仍记账
        verify(llmCallLogMapper).insert(any(LlmCallLog.class));
    }
}
