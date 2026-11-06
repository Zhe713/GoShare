package com.goshare.order.service;

import com.goshare.order.dto.OrderCreateRequest;
import com.goshare.order.dto.OrderVO;

import java.util.List;

/**
 * 订单服务
 */
public interface OrderService {

    /** 下单占座（防重复提交，原子扣减库存） */
    OrderVO create(Long passengerId, OrderCreateRequest request);

    /** 取消订单（待支付订单取消并回补库存） */
    OrderVO cancel(Long passengerId, String orderNo);

    /** 取消挽留预览（A2：按时间窗判责并计算违约金/退款，费用一律 FeeCalculator 纯 Java 计算） */
    com.goshare.order.util.FeeCalculator.FeeBreakdown cancelPreview(Long passengerId, String orderNo);

    /** 支付成功回调（幂等处理，待支付→已支付；会向支付服务校验流水真实性与金额） */
    OrderVO handlePayCallback(String orderNo, String paymentNo);

    /** 查询订单详情（仅本人可见） */
    OrderVO getByOrderNo(Long passengerId, String orderNo);

    /** 我的订单列表 */
    List<OrderVO> listByPassenger(Long passengerId);

    /** 定时释放超时未支付订单并回补库存，返回处理条数 */
    int releaseExpiredOrders();

    /** 按订单号关闭超时订单（RocketMQ 延时消息消费者调用；非 CREATED 状态静默跳过） */
    void closeExpiredOrderByNo(String orderNo);

    /** 候补登记：行程满座时排队（Redis 有序集合，按登记时间排序） */
    void joinWaitlist(Long passengerId, Long tripId);

    /** 退出候补 */
    void leaveWaitlist(Long passengerId, Long tripId);

    /** 车主视角：某行程的乘客订单名单（阶段1新增） */
    List<OrderVO> listByTrip(Long driverId, Long tripId);

    /** 车主确认乘客上车：PAID → BOARDED（阶段1履约闭环新增） */
    OrderVO board(Long driverId, Long tripId, String orderNo);

    /** 车主确认到达：BOARDED → COMPLETED（阶段1履约闭环新增） */
    OrderVO complete(Long driverId, Long tripId, String orderNo);

    /** 乘客评价：COMPLETED → REVIEWED，落评价并派生车主信任分（阶段1履约闭环新增） */
    OrderVO review(Long passengerId, String orderNo, int rating, String comment);

    /** 线路价格统计（F3 价格参谋：同线路近 N 天均价/最低/中位数，阶段3 AI 扩展） */
    com.goshare.order.dto.PriceStatsVO priceStats(String fromCity, String toCity, int days);

    /** 行程账单（F5：按乘客月度聚合次数/里程/花费/最常线路，数据全部来自真实订单表） */
    com.goshare.order.dto.TripBillVO tripBill(Long passengerId, String month);

    /**
     * 改签（F4）：PAID 订单改签到同线路新班次。
     * 改签免费；新价 ≤ 原实付 → 差额退回（部分退款流水）；新价更高 → 拒绝（提示不支持补差）。
     */
    OrderVO reschedule(Long passengerId, String orderNo, Long newTripId, Integer newFromStopSeq, Integer newToStopSeq);
}
