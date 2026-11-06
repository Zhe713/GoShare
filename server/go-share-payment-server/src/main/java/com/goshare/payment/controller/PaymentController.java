package com.goshare.payment.controller;

import com.goshare.common.result.Result;
import com.goshare.common.security.CurrentUserResolver;
import com.goshare.common.security.InternalApi;
import com.goshare.payment.dto.PayRequest;
import com.goshare.payment.dto.PaymentVO;
import com.goshare.payment.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 支付接口：发起模拟支付、查询支付结果
 */
@RestController
@RequestMapping("/api/payment")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;
    private final CurrentUserResolver currentUserResolver;

    /** 发起模拟支付（乘客登录后调用） */
    @PostMapping("/pay")
    public Result<PaymentVO> pay(@RequestHeader(value = "Authorization", required = false) String auth,
                                 @RequestBody @Valid PayRequest request) {
        Long passengerId = currentUserResolver.resolveUserId(auth);
        return Result.success(paymentService.pay(passengerId, request.getOrderNo()));
    }

    /** 查询支付结果 */
    @GetMapping("/{paymentNo}")
    public Result<PaymentVO> detail(@PathVariable("paymentNo") String paymentNo) {
        return Result.success(paymentService.getByPaymentNo(paymentNo));
    }

    /** 退款（阶段2退款域：订单服务内部调用，非用户 API） */
    @InternalApi
    @PostMapping("/refund")
    public Result<Boolean> refund(@RequestBody Map<String, Object> body) {
        String paymentNo = String.valueOf(body.get("paymentNo"));
        java.math.BigDecimal amount = body.get("amount") == null
                ? null : new java.math.BigDecimal(String.valueOf(body.get("amount")));
        return Result.success(paymentService.refund(paymentNo, amount));
    }
}
