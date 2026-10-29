package com.goshare.payment.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.goshare.common.exception.BusinessException;
import com.goshare.common.result.Result;
import com.goshare.payment.dto.OrderDTO;
import com.goshare.payment.dto.PaymentVO;
import com.goshare.payment.entity.Payment;
import com.goshare.payment.mapper.PaymentMapper;
import com.goshare.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 支付服务实现（演示：模拟扣款成功后回调订单服务）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private final PaymentMapper paymentMapper;
    private final RestTemplate restTemplate;

    @Value("${goshare.payment.order-service-url}")
    private String orderServiceUrl;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PaymentVO pay(Long passengerId, String orderNo) {
        // 1. 从订单服务获取订单（金额、状态）
        OrderDTO order = fetchOrder(orderNo);
        if (order == null) {
            throw new BusinessException(404, "订单不存在");
        }
        if (!"CREATED".equals(order.getStatus())) {
            throw new BusinessException(400, "订单当前状态不可支付");
        }

        // 2. 查已有支付记录：已成功则拒绝；有未完成记录则复用
        Payment payment = paymentMapper.selectOne(new LambdaQueryWrapper<Payment>()
                .eq(Payment::getOrderNo, orderNo)
                .eq(Payment::getStatus, "PENDING"));
        if (payment == null) {
            payment = new Payment();
            payment.setPaymentNo(generatePaymentNo());
            payment.setOrderNo(orderNo);
            payment.setPassengerId(passengerId);
            payment.setAmount(order.getPayPrice());
            payment.setStatus("PENDING");
            payment.setChannel("MOCK");
            paymentMapper.insert(payment);
        }

        // 3. 模拟支付渠道扣款成功
        log.info("模拟扣款成功 paymentNo={} amount={}", payment.getPaymentNo(), payment.getAmount());

        // 4. 回调订单服务：订单待支付 → 已支付（订单服务内部幂等）
        Result<?> callback = callOrderPayCallback(orderNo, payment.getPaymentNo());
        if (callback == null || callback.getCode() != 200) {
            payment.setStatus("FAILED");
            paymentMapper.updateById(payment);
            throw new BusinessException(500, "订单回调失败，支付未完成");
        }

        // 5. 本地支付记录置为成功
        payment.setStatus("SUCCESS");
        payment.setPaidAt(LocalDateTime.now());
        paymentMapper.updateById(payment);
        return toVO(payment);
    }

    @Override
    public PaymentVO getByPaymentNo(String paymentNo) {
        Payment payment = paymentMapper.selectOne(new LambdaQueryWrapper<Payment>()
                .eq(Payment::getPaymentNo, paymentNo));
        if (payment == null) {
            throw new BusinessException(404, "支付记录不存在");
        }
        return toVO(payment);
    }

    // ---------- 私有方法 ----------

    private OrderDTO fetchOrder(String orderNo) {
        try {
            Result<OrderDTO> result = restTemplate.exchange(
                            orderServiceUrl + "/api/order/" + orderNo,
                            HttpMethod.GET, null,
                            new ParameterizedTypeReference<Result<OrderDTO>>() {})
                    .getBody();
            if (result != null && result.getCode() == 200) {
                return result.getData();
            }
            return null;
        } catch (Exception e) {
            throw new BusinessException(500, "订单服务暂不可用，请稍后重试");
        }
    }

    private Result<?> callOrderPayCallback(String orderNo, String paymentNo) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, String>> entity = new HttpEntity<>(
                    Map.of("orderNo", orderNo, "paymentNo", paymentNo), headers);
            return restTemplate.exchange(
                            orderServiceUrl + "/api/order/pay-callback",
                            HttpMethod.POST, entity,
                            new ParameterizedTypeReference<Result<?>>() {})
                    .getBody();
        } catch (Exception e) {
            log.error("订单支付回调失败", e);
            return null;
        }
    }

    /** 支付流水号：P + yyyyMMddHHmmss + 6位随机数字 */
    private String generatePaymentNo() {
        return "P" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
    }

    private PaymentVO toVO(Payment payment) {
        PaymentVO vo = new PaymentVO();
        vo.setPaymentNo(payment.getPaymentNo());
        vo.setOrderNo(payment.getOrderNo());
        vo.setPassengerId(payment.getPassengerId());
        vo.setAmount(payment.getAmount());
        vo.setStatus(payment.getStatus());
        vo.setChannel(payment.getChannel());
        vo.setCreatedAt(payment.getCreatedAt());
        vo.setPaidAt(payment.getPaidAt());
        return vo;
    }
}
