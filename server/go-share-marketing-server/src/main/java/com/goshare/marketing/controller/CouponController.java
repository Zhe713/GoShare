package com.goshare.marketing.controller;

import com.goshare.common.result.Result;
import com.goshare.common.security.CurrentUserResolver;
import com.goshare.marketing.dto.ReceiveRequest;
import com.goshare.marketing.dto.CouponVO;
import com.goshare.marketing.dto.SettleRequest;
import com.goshare.marketing.dto.SettleResult;
import com.goshare.marketing.dto.CouponActionRequest;
import com.goshare.marketing.service.CouponService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 券接口：领券、我的券、用券结算、确认用券、退券
 */
@RestController
@RequestMapping("/api/coupon")
@RequiredArgsConstructor
public class CouponController {

    private final CouponService couponService;
    private final CurrentUserResolver currentUserResolver;

    /** 领券（秒杀） */
    @PostMapping("/receive")
    public Result<CouponVO> receive(@RequestHeader(value = "Authorization", required = false) String auth,
                                    @RequestBody @Valid ReceiveRequest request) {
        Long userId = currentUserResolver.resolveUserId(auth);
        return Result.success(couponService.receive(userId, request.getTemplateId()));
    }

    /** 我的券列表 */
    @GetMapping("/mine")
    public Result<List<CouponVO>> mine(@RequestHeader(value = "Authorization", required = false) String auth,
                                       @RequestParam(value = "status", required = false) Integer status) {
        Long userId = currentUserResolver.resolveUserId(auth);
        return Result.success(couponService.listMine(userId, status));
    }

    /** 用券结算（订单服务下单时调用，锁定券并返回抵扣金额） */
    @PostMapping("/settle")
    public Result<SettleResult> settle(@RequestBody @Valid SettleRequest request) {
        return Result.success(couponService.settle(
                request.getUserId(), request.getCouponId(), request.getOrderNo(), request.getTotalPrice()));
    }

    /** 确认用券（订单服务支付成功回调时调用） */
    @PostMapping("/confirm")
    public Result<Void> confirm(@RequestBody @Valid CouponActionRequest request) {
        couponService.confirmUse(request.getUserId(), request.getCouponId(), request.getOrderNo());
        return Result.success();
    }

    /** 退券（订单服务取消/超时关闭时调用） */
    @PostMapping("/return")
    public Result<Void> returnCoupon(@RequestBody @Valid CouponActionRequest request) {
        couponService.returnCoupon(request.getUserId(), request.getCouponId(), request.getOrderNo());
        return Result.success();
    }
}
