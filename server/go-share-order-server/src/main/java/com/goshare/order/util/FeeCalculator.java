package com.goshare.order.util;

import com.goshare.order.entity.TripOrder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 费用计算器（A2 取消挽留·费用解释的纯 Java 实现）
 * <p>
 * 设计约束：金额/违约金/退款一律由此计算，LLM 只负责把结果讲成人话（方案约定：A2 金额不靠 LLM）。
 * 判责规则对齐嘀嗒顺风车：城际订单发车前 2 小时内取消按实付 20% 收取违约金，单笔封顶 50 元；
 * 2 小时外取消无责免费；未支付（CREATED）订单取消无退款。
 */
public final class FeeCalculator {

    /** 违约金比例：实付金额的 20% */
    private static final BigDecimal PENALTY_RATE = new BigDecimal("0.20");

    /** 违约金封顶（元） */
    private static final BigDecimal PENALTY_CAP = new BigDecimal("50.00");

    /** 免责时间窗：发车前 120 分钟（城际） */
    private static final long FREE_CANCEL_MINUTES = 120;

    private FeeCalculator() {
    }

    /**
     * 计算取消费用明细
     *
     * @param order          订单（须含金额与状态）
     * @param departureTime  行程出发时间（来自 trip 服务）
     * @param now            当前时间
     */
    public static FeeBreakdown calc(TripOrder order, LocalDateTime departureTime, LocalDateTime now) {
        FeeBreakdown b = new FeeBreakdown();
        b.orderNo = order.getOrderNo();
        b.status = order.getStatus();
        b.totalPrice = order.getTotalPrice();
        b.couponDiscount = order.getCouponDiscount() == null ? BigDecimal.ZERO : order.getCouponDiscount();
        b.payPrice = order.getPayPrice();

        String status = order.getStatus();
        if ("CREATED".equals(status)) {
            // 未支付：取消不扣钱、不退钱（券会退回）
            b.liability = "无责";
            b.penalty = BigDecimal.ZERO;
            b.refundAmount = BigDecimal.ZERO;
            b.ruleExplain = "订单尚未支付，取消不产生违约金，占用的座位与优惠券会立即释放/退回。";
            b.feeExplain = "此单未支付，现在取消不扣任何费用，优惠券将退回你的券包。";
            return b;
        }
        if ("PAID".equals(status) || "IN_SERVICE".equals(status)) {
            long minutes = Duration.between(now, departureTime).toMinutes();
            if (minutes >= FREE_CANCEL_MINUTES) {
                b.liability = "无责";
                b.penalty = BigDecimal.ZERO;
                b.refundAmount = order.getPayPrice();
                b.ruleExplain = "距发车超过 2 小时（保护期），取消无责，全额退款。";
                b.feeExplain = "距发车还有 " + minutes + " 分钟（超过 2 小时保护期），现在取消不扣违约金，实付 "
                        + order.getPayPrice().toPlainString() + " 元全额退回。";
            } else {
                b.liability = "有责";
                BigDecimal penalty = order.getPayPrice().multiply(PENALTY_RATE).setScale(2, RoundingMode.HALF_UP);
                if (penalty.compareTo(PENALTY_CAP) > 0) {
                    penalty = PENALTY_CAP;
                }
                b.penalty = penalty;
                b.refundAmount = order.getPayPrice().subtract(penalty).max(BigDecimal.ZERO);
                b.ruleExplain = "距发车不足 2 小时，按实付金额 20% 收取违约金（封顶 50 元）。";
                b.feeExplain = "距发车只剩 " + minutes + " 分钟（不足 2 小时），需扣除违约金 "
                        + penalty.toPlainString() + " 元（实付 " + order.getPayPrice().toPlainString()
                        + " 元的 20%，封顶 50），预计退回 " + b.refundAmount.toPlainString() + " 元。";
            }
            return b;
        }
        // 已取消/已完成/超时：不可再算
        b.liability = "已结束";
        b.penalty = BigDecimal.ZERO;
        b.refundAmount = BigDecimal.ZERO;
        b.ruleExplain = "订单当前状态为 " + status + "，已不可取消。";
        b.feeExplain = "这笔订单已" + ("CANCELLED".equals(status) ? "取消" : "结束") + "，无需再处理取消。";
        return b;
    }

    /** 费用明细（金额一律 BigDecimal，避免浮点误差） */
    public static class FeeBreakdown {
        public String orderNo;
        public String status;
        public BigDecimal totalPrice;
        public BigDecimal couponDiscount;
        public BigDecimal payPrice;
        public String liability;
        public BigDecimal penalty;
        public BigDecimal refundAmount;
        public String ruleExplain;
        public String feeExplain;

        public String getOrderNo() {
            return orderNo;
        }

        public String getStatus() {
            return status;
        }

        public BigDecimal getTotalPrice() {
            return totalPrice;
        }

        public BigDecimal getCouponDiscount() {
            return couponDiscount;
        }

        public BigDecimal getPayPrice() {
            return payPrice;
        }

        public String getLiability() {
            return liability;
        }

        public BigDecimal getPenalty() {
            return penalty;
        }

        public BigDecimal getRefundAmount() {
            return refundAmount;
        }

        public String getRuleExplain() {
            return ruleExplain;
        }

        public String getFeeExplain() {
            return feeExplain;
        }
    }
}
