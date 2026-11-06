package com.goshare.order.controller;

import com.goshare.common.result.Result;
import com.goshare.common.security.CurrentUserResolver;
import com.goshare.order.dto.OrderCancelRequest;
import com.goshare.order.dto.OrderCreateRequest;
import com.goshare.order.dto.OrderVO;
import com.goshare.order.dto.PayCallbackRequest;
import com.goshare.order.dto.WaitlistRequest;
import com.goshare.order.service.OrderService;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OrderController 单测：所有端点都把「当前登录用户」解析出来再交给服务层，
 * 重点是确认 passengerId/driverId **不是从请求体里取**（否则就是越权入口）。
 */
class OrderControllerTest {

    private final OrderService orderService = mock(OrderService.class);
    private final CurrentUserResolver resolver = mock(CurrentUserResolver.class);
    private final OrderController controller = new OrderController(orderService, resolver);

    private OrderVO vo() {
        OrderVO v = new OrderVO();
        v.setOrderNo("20261106000000000001");
        v.setStatus("CREATED");
        return v;
    }

    private CurrentUserResolver stubUser() {
        when(resolver.resolveUserId("Bearer t")).thenReturn(3L);
        return resolver;
    }

    @Test
    void createUsesResolvedPassenger() {
        stubUser();
        when(orderService.create(eq(3L), any(OrderCreateRequest.class))).thenReturn(vo());

        Result<OrderVO> r = controller.create("Bearer t", new OrderCreateRequest());

        assertEquals(200, r.getCode());
        verify(orderService).create(eq(3L), any(OrderCreateRequest.class));
    }

    @Test
    void cancelUsesResolvedPassenger() {
        stubUser();
        when(orderService.cancel(eq(3L), anyString())).thenReturn(vo());
        OrderCancelRequest req = new OrderCancelRequest();
        req.setOrderNo("20261106000000000001");

        controller.cancel("Bearer t", req);

        verify(orderService).cancel(3L, "20261106000000000001");
    }

    @Test
    void cancelPreviewUsesResolvedPassenger() {
        stubUser();
        when(orderService.cancelPreview(eq(3L), anyString()))
                .thenReturn(new com.goshare.order.util.FeeCalculator.FeeBreakdown());
        OrderCancelRequest req = new OrderCancelRequest();
        req.setOrderNo("20261106000000000001");

        controller.cancelPreview("Bearer t", req);

        verify(orderService).cancelPreview(3L, "20261106000000000001");
    }

    @Test
    void payCallbackPassesFlowNumber() {
        when(orderService.handlePayCallback("20261106000000000001", "P001")).thenReturn(vo());
        PayCallbackRequest req = new PayCallbackRequest();
        req.setOrderNo("20261106000000000001");
        req.setPaymentNo("P001");

        assertEquals(200, controller.payCallback(req).getCode());
    }

    @Test
    void detailAndInternalDetail() {
        stubUser();
        when(orderService.getByOrderNo(anyLong(), anyString())).thenReturn(vo());

        controller.detail("Bearer t", "20261106000000000001");
        controller.internalDetail("20261106000000000001", 9L);

        // 内部接口不传 passengerId 时按"不校验归属"处理
        verify(orderService).getByOrderNo(3L, "20261106000000000001");
        verify(orderService).getByOrderNo(9L, "20261106000000000001");
    }

    @Test
    void internalCancelPreviewWithoutPassengerId() {
        when(orderService.cancelPreview(isNull(), anyString()))
                .thenReturn(new com.goshare.order.util.FeeCalculator.FeeBreakdown());
        OrderCancelRequest req = new OrderCancelRequest();
        req.setOrderNo("20261106000000000001");

        assertEquals(200, controller.internalCancelPreview(null, req).getCode());
    }

    @Test
    void mineUsesResolvedPassenger() {
        stubUser();
        when(orderService.listByPassenger(3L)).thenReturn(List.of(vo()));

        assertEquals(1, controller.mine("Bearer t").getData().size());
    }

    @Test
    void waitlistEndpointsForwardPassengerAndTrip() {
        stubUser();
        WaitlistRequest req = new WaitlistRequest();
        req.setTripId(5L);

        controller.joinWaitlist("Bearer t", req);
        controller.leaveWaitlist("Bearer t", req);

        verify(orderService).joinWaitlist(3L, 5L);
        verify(orderService).leaveWaitlist(3L, 5L);
    }

    @Test
    void fulfillmentEndpointsForwardResolvedDriver() {
        stubUser();
        when(orderService.listByTrip(3L, 5L)).thenReturn(List.of(vo()));
        when(orderService.board(3L, 5L, "N1")).thenReturn(vo());
        when(orderService.complete(3L, 5L, "N1")).thenReturn(vo());
        when(orderService.review(eq(3L), eq("N1"), eq(5), isNull())).thenReturn(vo());

        assertEquals(1, controller.listByTrip("Bearer t", 5L).getData().size());
        controller.board("Bearer t", 5L, "N1");
        controller.complete("Bearer t", 5L, "N1");

        Map<String, Object> body = new HashMap<>();
        body.put("orderNo", "N1");
        body.put("rating", 5);
        controller.review("Bearer t", body);

        verify(orderService).board(3L, 5L, "N1");
        verify(orderService).complete(3L, 5L, "N1");
        verify(orderService).review(3L, "N1", 5, null);
    }

    @Test
    void priceStatsAndBill() {
        stubUser();
        when(orderService.priceStats("石家庄", "邯郸", 30))
                .thenReturn(new com.goshare.order.dto.PriceStatsVO());
        when(orderService.tripBill(3L, null)).thenReturn(new com.goshare.order.dto.TripBillVO());

        assertEquals(200, controller.priceStats("石家庄", "邯郸", 30).getCode());
        assertEquals(200, controller.bill("Bearer t", null).getCode());
    }

    @Test
    void rescheduleParsesOptionalSegments() {
        stubUser();
        when(orderService.reschedule(3L, "N1", 7L, 0, 1)).thenReturn(vo());
        when(orderService.reschedule(3L, "N2", 7L, null, null)).thenReturn(vo());

        Map<String, Object> body = new HashMap<>();
        body.put("orderNo", "N1");
        body.put("newTripId", 7);
        body.put("fromStopSeq", 0);
        body.put("toStopSeq", 1);
        controller.reschedule("Bearer t", body);

        // 不传区间：由服务层沿用原订单区间
        Map<String, Object> minimal = new HashMap<>();
        minimal.put("orderNo", "N2");
        minimal.put("newTripId", 7);
        controller.reschedule("Bearer t", minimal);

        verify(orderService).reschedule(3L, "N1", 7L, 0, 1);
        verify(orderService).reschedule(3L, "N2", 7L, null, null);
    }
}
