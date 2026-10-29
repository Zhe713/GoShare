package com.goshare.payment.controller;

import com.goshare.common.result.Result;
import com.goshare.common.security.CurrentUserResolver;
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
}
