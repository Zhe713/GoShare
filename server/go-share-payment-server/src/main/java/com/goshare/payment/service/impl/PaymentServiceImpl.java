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

import java.math.BigDecimal;
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
    public PaymentVO pay(Long passengerId, String orderNo) {
        // 1. 从订单服务获取订单（金额、状态）
        OrderDTO order = fetchOrder(orderNo);
        if (order == null) {
            throw new BusinessException(404, "订单不存在");
        }
        if (!"CREATED".equals(order.getStatus())) {
            throw new BusinessException(400, "订单当前状态不可支付");
        }
        // 只能给自己的订单付款（此前任何人可以替他人下单付款）
        if (order.getPassengerId() != null && !order.getPassengerId().equals(passengerId)) {
            throw new BusinessException(403, "无权支付他人订单");
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
            // DB 的 DEFAULT CURRENT_TIMESTAMP 不会回填实体，显式赋值才能让返回的 VO 带上创建时间
            payment.setCreatedAt(LocalDateTime.now());
            paymentMapper.insert(payment);
        }

        // 3. 模拟支付渠道扣款成功，并**先把成功流水落库**
        //    顺序很关键：订单服务现在会回查流水来校验回调真实性，
        //    如果先回调再落库，订单侧必然查不到这笔 SUCCESS 流水而拒绝支付。
        log.info("模拟扣款成功 paymentNo={} amount={}", payment.getPaymentNo(), payment.getAmount());
        payment.setStatus("SUCCESS");
        payment.setPaidAt(LocalDateTime.now());
        paymentMapper.updateById(payment);

        // 4. 回调订单服务：订单待支付 → 已支付（订单服务内部幂等 + 校验流水与金额）
        Result<?> callback = callOrderPayCallback(orderNo, payment.getPaymentNo());
        if (callback == null || callback.getCode() != 200) {
            // 注意：这里**不能**把流水改回 FAILED。
            // 钱已经扣了，把成功流水改成失败等于"资金事实被业务失败抹掉"，
            // 真实渠道下会直接造成资损和对账不平。正确做法是保留 SUCCESS，
            // 由补偿任务（阶段 2 的对账）负责把订单侧补齐。
            log.error("扣款成功但订单回调失败，流水保持 SUCCESS 等待补偿 orderNo={} paymentNo={}",
                    orderNo, payment.getPaymentNo());
            throw new BusinessException(500, "支付已成功，但订单状态同步失败，请稍后刷新");
        }
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

    /**
     * 退款（阶段2退款域）。
     *
     * <p>演示渠道 MOCK：退款立即成功，把原支付流水置为 REFUNDED。
     * 真实渠道下这里应调用第三方退款 API 并回写渠道流水号，此处简化。
     */
    @Override
    public boolean refund(String paymentNo, BigDecimal amount) {
        Payment payment = paymentMapper.selectOne(new LambdaQueryWrapper<Payment>()
                .eq(Payment::getPaymentNo, paymentNo));
        if (payment == null) {
            log.warn("退款失败：支付流水不存在 paymentNo={}", paymentNo);
            return false;
        }
        if (!"SUCCESS".equals(payment.getStatus())) {
            log.warn("退款失败：支付流水状态不允许退款 paymentNo={} status={}", paymentNo, payment.getStatus());
            return false;
        }
        if (amount != null && payment.getAmount() != null
                && amount.compareTo(payment.getAmount()) > 0) {
            log.warn("退款失败：退款金额超过支付金额 paymentNo={} refund={} paid={}",
                    paymentNo, amount, payment.getAmount());
            return false;
        }
        // 模拟退款成功
        payment.setStatus("REFUNDED");
        paymentMapper.updateById(payment);
        log.info("退款成功 paymentNo={} amount={}", paymentNo, amount);
        return true;
    }

    // ---------- 私有方法 ----------

    private OrderDTO fetchOrder(String orderNo) {
        try {
            Result<OrderDTO> result = restTemplate.exchange(
                            orderServiceUrl + "/api/order/internal/" + orderNo,
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
