package com.goshare.order.entity;

/**
 * 订单状态机常量（阶段1「换骨」重定义）
 *
 * <p>原状态机只有 CREATED → PAID 一条出口，IN_SERVICE/COMPLETED 全仓零写入。
 * 换骨后补全履约生命周期与退款闭环：
 *
 * <pre>
 * CREATED ──支付──> PAID ──上车──> BOARDED ──到达──> COMPLETED ──评价──> REVIEWED
 *    │                │
 *    ├──取消──> CANCELLED
 *    │               └──退款──> REFUNDING ──> REFUNDED
 *    └──超时──> EXPIRED
 * </pre>
 */
public final class OrderStatus {

    private OrderStatus() {
    }

    /** 待支付 */
    public static final String CREATED = "CREATED";
    /** 已支付 */
    public static final String PAID = "PAID";
    /** 已上车（服务中） */
    public static final String BOARDED = "BOARDED";
    /** 已完成（到达） */
    public static final String COMPLETED = "COMPLETED";
    /** 已评价 */
    public static final String REVIEWED = "REVIEWED";
    /** 已取消 */
    public static final String CANCELLED = "CANCELLED";
    /** 超时关闭 */
    public static final String EXPIRED = "EXPIRED";
    /** 退款中 */
    public static final String REFUNDING = "REFUNDING";
    /** 已退款 */
    public static final String REFUNDED = "REFUNDED";
}
