package com.goshare.trip.controller;

import com.goshare.common.result.Result;
import com.goshare.common.security.CurrentUserResolver;
import com.goshare.trip.dto.TripPublishRequest;
import com.goshare.trip.dto.TripVO;
import com.goshare.trip.service.TripService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TripController 单测：发布有车主角色门禁，其余端点透传。
 * 发布门禁是权限边界（普通乘客不能发车），必须单独断言。
 */
class TripControllerTest {

    private final TripService tripService = mock(TripService.class);
    private final CurrentUserResolver resolver = mock(CurrentUserResolver.class);
    private final TripController controller = new TripController(tripService, resolver);

    private TripVO vo(long id) {
        TripVO v = new TripVO();
        v.setId(id);
        return v;
    }

    private TripPublishRequest req() {
        TripPublishRequest r = new TripPublishRequest();
        r.setFromCity("石家庄");
        r.setToCity("邯郸");
        r.setDepartureTime(LocalDateTime.now().plusDays(2));
        r.setTotalSeats(3);
        return r;
    }

    @Test
    void publishRejectsNonDriver() {
        when(resolver.resolveUserId("Bearer p")).thenReturn(2L);
        when(resolver.resolveRole("Bearer p")).thenReturn("PASSENGER");

        Result<TripVO> result = controller.publish("Bearer p", req());

        assertEquals(403, result.getCode());
        verify(tripService, never()).publish(anyLong(), any());
    }

    @Test
    void publishAllowsDriver() {
        when(resolver.resolveUserId("Bearer d")).thenReturn(1L);
        when(resolver.resolveRole("Bearer d")).thenReturn("DRIVER");
        when(tripService.publish(anyLong(), any())).thenReturn(vo(20L));

        Result<TripVO> result = controller.publish("Bearer d", req());

        assertEquals(200, result.getCode());
        assertEquals(20L, result.getData().getId());
    }

    @Test
    void searchDelegates() {
        when(tripService.search(any(), any(), any())).thenReturn(List.of(vo(1L)));

        Result<List<TripVO>> result = controller.search("石家庄", "邯郸", LocalDate.of(2026, 11, 8));

        assertEquals(1, result.getData().size());
    }

    @Test
    void detailDelegates() {
        when(tripService.detail(5L)).thenReturn(vo(5L));
        assertEquals(5L, controller.detail(5L).getData().getId());
    }

    @Test
    void mineUsesResolvedDriver() {
        when(resolver.resolveUserId("Bearer d")).thenReturn(1L);
        when(tripService.listByDriver(1L)).thenReturn(List.of(vo(3L)));

        assertEquals(1, controller.mine("Bearer d").getData().size());
    }

    @Test
    void esSyncReturnsCount() {
        when(tripService.syncAllToEs()).thenReturn(7);
        assertEquals(7, controller.esSync().getData());
    }

    @Test
    void lifecycleEndpointsForwardResolvedDriver() {
        when(resolver.resolveUserId("Bearer d")).thenReturn(1L);
        when(tripService.cancelTrip(1L, 5L)).thenReturn(vo(5L));
        when(tripService.depart(1L, 5L)).thenReturn(vo(5L));
        when(tripService.complete(1L, 5L)).thenReturn(vo(5L));

        assertTrue(controller.cancelTrip("Bearer d", 5L).getCode() == 200);
        assertTrue(controller.depart("Bearer d", 5L).getCode() == 200);
        assertTrue(controller.complete("Bearer d", 5L).getCode() == 200);
        verify(tripService).cancelTrip(1L, 5L);
        verify(tripService).depart(1L, 5L);
        verify(tripService).complete(1L, 5L);
    }
}
