package com.goshare.marketing.controller;

import com.goshare.common.result.Result;
import com.goshare.common.security.CurrentUserResolver;
import com.goshare.marketing.dto.CouponActionRequest;
import com.goshare.marketing.dto.CouponVO;
import com.goshare.marketing.dto.ReceiveRequest;
import com.goshare.marketing.dto.SettleRequest;
import com.goshare.marketing.dto.SettleResult;
import com.goshare.marketing.service.CouponService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 券控制器：领券 / 我的券 / 用券结算 / 确认用券 / 退券
 */
class CouponControllerTest {

    private CouponService couponService;
    private CurrentUserResolver resolver;
    private CouponController controller;

    @BeforeEach
    void setUp() {
        couponService = mock(CouponService.class);
        resolver = mock(CurrentUserResolver.class);
        controller = new CouponController(couponService, resolver);
        when(resolver.resolveUserId(anyString())).thenReturn(2L);
    }

    @Test
    void receiveDelegatesToService() {
        ReceiveRequest req = new ReceiveRequest();
        req.setTemplateId(10L);
        CouponVO vo = new CouponVO();
        vo.setId(1L);
        when(couponService.receive(2L, 10L)).thenReturn(vo);

        Result<CouponVO> resp = controller.receive("Bearer t", req);

        assertEquals(200, resp.getCode());
        assertEquals(1L, resp.getData().getId());
    }

    @Test
    void mineDelegatesWithStatus() {
        when(couponService.listMine(2L, 0)).thenReturn(List.of(new CouponVO()));

        Result<List<CouponVO>> resp = controller.mine("Bearer t", 0);

        assertEquals(1, resp.getData().size());
    }

    @Test
    void settleDelegates() {
        SettleRequest req = new SettleRequest();
        req.setUserId(2L);
        req.setCouponId(1L);
        req.setOrderNo("20261030151932907564");
        req.setTotalPrice(new BigDecimal("40"));
        SettleResult sr = new SettleResult();
        sr.setDiscount(new BigDecimal("5"));
        when(couponService.settle(2L, 1L, "20261030151932907564", new BigDecimal("40"))).thenReturn(sr);

        Result<SettleResult> resp = controller.settle(req);

        assertEquals(0, resp.getData().getDiscount().compareTo(new BigDecimal("5")));
    }

    @Test
    void confirmDelegates() {
        CouponActionRequest req = new CouponActionRequest();
        req.setUserId(2L);
        req.setCouponId(1L);
        req.setOrderNo("20261030151932907564");

        Result<Void> resp = controller.confirm(req);

        verify(couponService).confirmUse(2L, 1L, "20261030151932907564");
        assertEquals(200, resp.getCode());
    }

    @Test
    void returnCouponDelegates() {
        CouponActionRequest req = new CouponActionRequest();
        req.setUserId(2L);
        req.setCouponId(1L);
        req.setOrderNo("20261030151932907564");

        Result<Void> resp = controller.returnCoupon(req);

        verify(couponService).returnCoupon(2L, 1L, "20261030151932907564");
        assertEquals(200, resp.getCode());
    }
}
