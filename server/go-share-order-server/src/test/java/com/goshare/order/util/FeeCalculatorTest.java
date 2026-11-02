package com.goshare.order.util;

import com.goshare.order.entity.TripOrder;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A2 取消挽留·费用计算器单测：判责时间窗边界 + 违约金比例/封顶
 */
class FeeCalculatorTest {

    private TripOrder order(String status, BigDecimal payPrice) {
        TripOrder o = new TripOrder();
        o.setOrderNo("T2026103001");
        o.setStatus(status);
        o.setTotalPrice(payPrice);
        o.setCouponDiscount(BigDecimal.ZERO);
        o.setPayPrice(payPrice);
        return o;
    }

    private LocalDateTime now = LocalDateTime.of(2026, 10, 30, 12, 0);

    @Test
    void 未支付订单取消无违约金无退款() {
        FeeCalculator.FeeBreakdown b = FeeCalculator.calc(order("CREATED", new BigDecimal("40.00")),
                now.plusHours(3), now);
        assertEquals("无责", b.liability);
        assertEquals(0, b.penalty.compareTo(BigDecimal.ZERO));
        assertEquals(0, b.refundAmount.compareTo(BigDecimal.ZERO));
        assertTrue(b.feeExplain.contains("未支付"));
    }

    @Test
    void 距发车正好120分钟无责全额退() {
        FeeCalculator.FeeBreakdown b = FeeCalculator.calc(order("PAID", new BigDecimal("40.00")),
                now.plusMinutes(120), now);
        assertEquals("无责", b.liability);
        assertEquals(0, b.penalty.compareTo(BigDecimal.ZERO));
        assertEquals(0, b.refundAmount.compareTo(new BigDecimal("40.00")));
    }

    @Test
    void 距发车119分钟有责按实付20percent() {
        FeeCalculator.FeeBreakdown b = FeeCalculator.calc(order("PAID", new BigDecimal("40.00")),
                now.plusMinutes(119), now);
        assertEquals("有责", b.liability);
        // 40 * 0.2 = 8.00
        assertEquals(0, b.penalty.compareTo(new BigDecimal("8.00")));
        // 40 - 8 = 32
        assertEquals(0, b.refundAmount.compareTo(new BigDecimal("32.00")));
    }

    @Test
    void 大额实付违约金封顶50元() {
        FeeCalculator.FeeBreakdown b = FeeCalculator.calc(order("PAID", new BigDecimal("300.00")),
                now.plusMinutes(30), now);
        assertEquals("有责", b.liability);
        // 300 * 0.2 = 60 > 50 → 封顶 50
        assertEquals(0, b.penalty.compareTo(new BigDecimal("50.00")));
        assertEquals(0, b.refundAmount.compareTo(new BigDecimal("250.00")));
    }

    @Test
    void 已取消订单不可再算() {
        FeeCalculator.FeeBreakdown b = FeeCalculator.calc(order("CANCELLED", new BigDecimal("40.00")),
                now.plusHours(1), now);
        assertEquals("已结束", b.liability);
        assertEquals(0, b.refundAmount.compareTo(BigDecimal.ZERO));
    }

    @Test
    void 服务中订单也按时间窗判责() {
        FeeCalculator.FeeBreakdown b = FeeCalculator.calc(order("IN_SERVICE", new BigDecimal("35.00")),
                now.plusMinutes(60), now);
        assertEquals("有责", b.liability);
        assertEquals(0, b.penalty.compareTo(new BigDecimal("7.00")));
    }

    @Test
    void 明细字段getter可读() {
        TripOrder o = order("PAID", new BigDecimal("40.00"));
        o.setCouponDiscount(new BigDecimal("5.00"));
        FeeCalculator.FeeBreakdown b = FeeCalculator.calc(o, now.plusMinutes(60), now);
        // getter 全覆盖（供 A2 挽留服务回显）
        assertEquals("T2026103001", b.getOrderNo());
        assertEquals("PAID", b.getStatus());
        assertEquals(0, b.getTotalPrice().compareTo(new BigDecimal("40.00")));
        assertEquals(0, b.getCouponDiscount().compareTo(new BigDecimal("5.00")));
        assertEquals(0, b.getPayPrice().compareTo(new BigDecimal("40.00")));
        assertEquals("有责", b.getLiability());
        assertEquals(0, b.getPenalty().compareTo(new BigDecimal("8.00")));
        assertEquals(0, b.getRefundAmount().compareTo(new BigDecimal("32.00")));
        assertTrue(b.getRuleExplain().contains("不足 2 小时"));
        assertTrue(b.getFeeExplain().contains("违约金"));
    }
}
