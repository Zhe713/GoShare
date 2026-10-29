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

    /** 支付成功回调（幂等处理，待支付→已支付） */
    OrderVO handlePayCallback(String orderNo, String paymentNo);

    /** 查询订单详情 */
    OrderVO getByOrderNo(String orderNo);

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
}
