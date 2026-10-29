package com.goshare.order.controller;

import com.goshare.common.result.Result;
import com.goshare.common.security.CurrentUserResolver;
import com.goshare.order.dto.OrderCancelRequest;
import com.goshare.order.dto.OrderCreateRequest;
import com.goshare.order.dto.OrderVO;
import com.goshare.order.dto.PayCallbackRequest;
import com.goshare.order.dto.WaitlistRequest;
import com.goshare.order.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 订单接口：下单、取消、支付回调、查询
 */
@RestController
@RequestMapping("/api/order")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final CurrentUserResolver currentUserResolver;

    /** 下单占座 */
    @PostMapping("/create")
    public Result<OrderVO> create(@RequestHeader(value = "Authorization", required = false) String auth,
                                  @RequestBody @Valid OrderCreateRequest request) {
        Long passengerId = currentUserResolver.resolveUserId(auth);
        return Result.success(orderService.create(passengerId, request));
    }

    /** 取消订单 */
    @PostMapping("/cancel")
    public Result<OrderVO> cancel(@RequestHeader(value = "Authorization", required = false) String auth,
                                  @RequestBody @Valid OrderCancelRequest request) {
        Long passengerId = currentUserResolver.resolveUserId(auth);
        return Result.success(orderService.cancel(passengerId, request.getOrderNo()));
    }

    /** 支付成功回调（支付服务调用，幂等） */
    @PostMapping("/pay-callback")
    public Result<OrderVO> payCallback(@RequestBody @Valid PayCallbackRequest request) {
        return Result.success(orderService.handlePayCallback(request.getOrderNo(), request.getPaymentNo()));
    }

    /** 订单详情 */
    @GetMapping("/{orderNo}")
    public Result<OrderVO> detail(@PathVariable("orderNo") String orderNo) {
        return Result.success(orderService.getByOrderNo(orderNo));
    }

    /** 我的订单 */
    @GetMapping("/mine")
    public Result<List<OrderVO>> mine(@RequestHeader(value = "Authorization", required = false) String auth) {
        Long passengerId = currentUserResolver.resolveUserId(auth);
        return Result.success(orderService.listByPassenger(passengerId));
    }

    /** 候补登记：行程满座时排队等座位 */
    @PostMapping("/waitlist")
    public Result<Void> joinWaitlist(@RequestHeader(value = "Authorization", required = false) String auth,
                                     @RequestBody @Valid WaitlistRequest request) {
        Long passengerId = currentUserResolver.resolveUserId(auth);
        orderService.joinWaitlist(passengerId, request.getTripId());
        return Result.success(null);
    }

    /** 退出候补 */
    @PostMapping("/waitlist/leave")
    public Result<Void> leaveWaitlist(@RequestHeader(value = "Authorization", required = false) String auth,
                                      @RequestBody @Valid WaitlistRequest request) {
        Long passengerId = currentUserResolver.resolveUserId(auth);
        orderService.leaveWaitlist(passengerId, request.getTripId());
        return Result.success(null);
    }
}
