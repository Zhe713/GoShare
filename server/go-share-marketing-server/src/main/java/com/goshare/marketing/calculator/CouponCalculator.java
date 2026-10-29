package com.goshare.marketing.calculator;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 券结算计算器（纯 Java 算钱，规则链）：
 * - 满减 FULL_REDUCTION：满 X 减 Y
 * - 立减 CASH：无门槛直接减 Y
 * - 折扣 DISCOUNT：按折扣率付费（85 = 8.5 折）
 */
public final class CouponCalculator {

    private CouponCalculator() {
    }

    /** 计算一张券能抵扣多少钱，返回抵扣金额（不为负、不超过订单总额） */
    public static BigDecimal calcDiscount(String type, BigDecimal threshold,
                                          BigDecimal discountValue, BigDecimal totalPrice) {
        switch (type) {
            case "FULL_REDUCTION":
                if (totalPrice.compareTo(threshold) < 0) {
                    return BigDecimal.ZERO; // 未满门槛，不能抵扣
                }
                return min(discountValue, totalPrice);
            case "CASH":
                return min(discountValue, totalPrice);
            case "DISCOUNT":
                // 实付 = 总额 × 折扣率 ÷ 100（四舍五入保留 2 位），抵扣 = 总额 - 实付
                BigDecimal pay = totalPrice.multiply(discountValue)
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                return totalPrice.subtract(pay).max(BigDecimal.ZERO);
            default:
                throw new IllegalArgumentException("未知券类型: " + type);
        }
    }

    private static BigDecimal min(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}
