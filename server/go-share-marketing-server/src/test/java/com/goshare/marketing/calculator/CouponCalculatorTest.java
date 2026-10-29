package com.goshare.marketing.calculator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 券结算计算器单元测试
 */
class CouponCalculatorTest {

    @Test
    @DisplayName("满减券不够门槛不能抵扣")
    void fullReductionBelowThreshold() {
        BigDecimal discount = CouponCalculator.calcDiscount(
                "FULL_REDUCTION", new BigDecimal("50"), new BigDecimal("10"), new BigDecimal("40"));
        assertEquals(0, discount.compareTo(BigDecimal.ZERO));
    }

    @Test
    @DisplayName("满减券够门槛按面值抵扣")
    void fullReductionMeetThreshold() {
        BigDecimal discount = CouponCalculator.calcDiscount(
                "FULL_REDUCTION", new BigDecimal("50"), new BigDecimal("10"), new BigDecimal("60"));
        assertEquals(0, discount.compareTo(new BigDecimal("10")));
    }

    @Test
    @DisplayName("立减券无门槛直接抵扣")
    void cashCoupon() {
        BigDecimal discount = CouponCalculator.calcDiscount(
                "CASH", BigDecimal.ZERO, new BigDecimal("5"), new BigDecimal("30"));
        assertEquals(0, discount.compareTo(new BigDecimal("5")));
    }

    @Test
    @DisplayName("折扣券按折扣率抵扣（85折抵扣15%）")
    void discountCoupon() {
        BigDecimal discount = CouponCalculator.calcDiscount(
                "DISCOUNT", BigDecimal.ZERO, new BigDecimal("85"), new BigDecimal("100"));
        assertEquals(0, discount.compareTo(new BigDecimal("15.00")));
    }

    @Test
    @DisplayName("抵扣金额不会超过订单总额")
    void discountNeverExceedTotal() {
        BigDecimal discount = CouponCalculator.calcDiscount(
                "CASH", BigDecimal.ZERO, new BigDecimal("100"), new BigDecimal("30"));
        assertEquals(0, discount.compareTo(new BigDecimal("30")));
    }

    @Test
    @DisplayName("满减金额大于订单总额时按订单总额抵扣")
    void reductionCappedByTotal() {
        BigDecimal discount = CouponCalculator.calcDiscount(
                "FULL_REDUCTION", new BigDecimal("10"), new BigDecimal("50"), new BigDecimal("20"));
        assertEquals(0, discount.compareTo(new BigDecimal("20")));
    }
}
