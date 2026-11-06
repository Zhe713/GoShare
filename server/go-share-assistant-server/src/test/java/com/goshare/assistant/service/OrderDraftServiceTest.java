package com.goshare.assistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.goshare.assistant.dto.OrderDraftVO;
import com.goshare.common.result.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F1 对话式下单的确认卡生成：解析 → 查真实班次 → 确定性算价。
 * 这里断言的都是"AI 不能自己决定"的东西：班次来自 trip 服务、金额由代码算、余座不足必须拒绝。
 */
class OrderDraftServiceTest {

    private RestTemplate restTemplate;
    private OrderDraftService service;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        service = new OrderDraftService(restTemplate, new ObjectMapper());
        ReflectionTestUtils.setField(service, "tripUrl", "http://trip:8002");
    }

    private Map<String, Object> trip(long id, String dep, int remain, double price, Double perKm, Double totalKm) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("id", id);
        t.put("fromCity", "石家庄");
        t.put("toCity", "邯郸");
        t.put("departureTime", dep);
        t.put("remainSeats", remain);
        t.put("price", price);
        t.put("status", 0);
        if (perKm != null) {
            t.put("pricePerKm", perKm);
        }
        if (totalKm != null) {
            t.put("totalDistanceKm", totalKm);
        }
        List<Map<String, Object>> stops = new ArrayList<>();
        stops.add(Map.of("city", "石家庄"));
        stops.add(Map.of("city", "邢台"));
        stops.add(Map.of("city", "邯郸"));
        t.put("stops", stops);
        return t;
    }

    @SuppressWarnings("unchecked")
    private void mockTrips(List<Map<String, Object>> trips) {
        when(restTemplate.exchange(contains("/api/trip/search"), any(), any(),
                any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(Result.success(trips)));
    }

    @Test
    void draftWithoutDestinationAsksForIt() {
        OrderDraftVO vo = service.draft("我想订票");

        assertFalse(vo.getOrderable());
        assertTrue(vo.getMessage().contains("目的地"));
    }

    @Test
    void draftWithoutOriginAsksForIt() {
        OrderDraftVO vo = service.draft("订去邯郸的");

        assertFalse(vo.getOrderable());
        assertTrue(vo.getMessage().contains("出发城市"));
    }

    @Test
    void draftReportsNoTrips() {
        mockTrips(List.of());

        OrderDraftVO vo = service.draft("从石家庄订去邯郸的");

        assertFalse(vo.getOrderable());
        assertTrue(vo.getMessage().contains("暂无班次"));
    }

    @Test
    void draftUsesIntervalPricingForEstimate() {
        // 0.32 元/公里 × 200 公里 = 64 元/座（与 order 服务下单口径一致）
        LocalDateTime dep = LocalDate.now().plusDays(2).atTime(10, 0);
        mockTrips(List.of(trip(11L, dep.toString(), 6, 40.0, 0.32, 200.0)));

        OrderDraftVO vo = service.draft("从石家庄订去邯郸的，两个人");

        assertTrue(vo.getOrderable());
        assertEquals(11L, vo.getTripId());
        assertEquals(2, vo.getSeatCount());
        assertEquals(0, vo.getUnitPrice().compareTo(new java.math.BigDecimal("64.00")));
        assertEquals(0, vo.getEstimatedTotal().compareTo(new java.math.BigDecimal("128.00")));
        assertEquals(List.of("石家庄", "邢台", "邯郸"), vo.getStopCities());
        assertNotNull(vo.getCancelPolicy());
    }

    @Test
    void draftFallsBackToTripPriceWithoutPerKm() {
        LocalDateTime dep = LocalDate.now().plusDays(2).atTime(10, 0);
        mockTrips(List.of(trip(12L, dep.toString(), 6, 40.0, null, null)));

        OrderDraftVO vo = service.draft("从石家庄订去邯郸的，一个人");

        assertEquals(0, vo.getUnitPrice().compareTo(new java.math.BigDecimal("40.0")));
    }

    @Test
    void draftRejectsWhenSeatsInsufficient() {
        LocalDateTime dep = LocalDate.now().plusDays(2).atTime(10, 0);
        mockTrips(List.of(trip(13L, dep.toString(), 1, 40.0, null, null)));

        OrderDraftVO vo = service.draft("从石家庄订去邯郸的，两个人");

        assertFalse(vo.getOrderable());
        assertTrue(vo.getMessage().contains("仅剩 1 座"));
    }

    @Test
    void draftSkipsFullAndDepartedTrips() {
        String future = LocalDate.now().plusDays(2).atTime(10, 0).toString();
        mockTrips(List.of(
                trip(14L, future, 0, 40.0, null, null),                       // 满座
                trip(15L, LocalDateTime.now().minusHours(1).toString(), 5, 40.0, null, null), // 已发车
                trip(16L, future, 3, 40.0, null, null)));                     // 可用

        OrderDraftVO vo = service.draft("从石家庄订去邯郸的，一个人");

        assertTrue(vo.getOrderable());
        assertEquals(16L, vo.getTripId());
    }

    @Test
    void draftRespectsHourPreference() {
        LocalDate base = LocalDate.now().plusDays(2);
        mockTrips(List.of(
                trip(17L, base.atTime(8, 0).toString(), 3, 40.0, null, null),
                trip(18L, base.atTime(15, 0).toString(), 3, 40.0, null, null)));

        // 说"下午3点"应选 15:00 那班，而不是最早的 08:00
        OrderDraftVO vo = service.draft("从石家庄订后天下午3点去邯郸的");

        assertTrue(vo.getOrderable());
        assertEquals(18L, vo.getTripId());
    }

    @Test
    void draftHandlesServiceFailureGracefully() {
        when(restTemplate.exchange(contains("/api/trip/search"), any(), any(),
                any(ParameterizedTypeReference.class)))
                .thenThrow(new RuntimeException("trip down"));

        OrderDraftVO vo = service.draft("从石家庄订去邯郸的");

        assertFalse(vo.getOrderable());
        assertNull(vo.getTripId());
    }
}
