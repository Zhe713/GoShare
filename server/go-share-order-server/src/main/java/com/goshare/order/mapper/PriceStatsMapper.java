package com.goshare.order.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 线路价格统计（F3 价格参谋的数据源，阶段3 AI 扩展）
 *
 * <p>统计「同线路（from_city→to_city）近 N 天」已支付订单的实付价格分布。
 * 纯统计、确定性计算，零幻觉风险——AI 只负责把数字讲成人话，金额全部来自真实订单表。
 *
 * <p>实现说明：订单表按 passenger_id 分片，跨乘客的线路统计无法用分片键路由。
 * 故分两步：① 查 trip 表（单表，不分片）拿该线路的 trip_id 列表；
 * ② 用逻辑表 trip_order 按 trip_id 查询（ShardingSphere 自动广播到两个分片并合并）。
 * 统计函数（avg/min/中位数）在 Java 层计算，避免依赖 ShardingSphere 的跨分片聚合重写。
 */
public interface PriceStatsMapper {

    /** 查 trip 表该线路的行程 ID 列表（单表查询，不分片） */
    @Select("SELECT id FROM trip WHERE from_city = #{fromCity} AND to_city = #{toCity}")
    List<Long> selectTripIds(@Param("fromCity") String fromCity, @Param("toCity") String toCity);

    /**
     * 查该行程列表近 N 天已支付订单的实付价格（逻辑表广播到分片）。
     * 只返回价格，统计在 Java 层做。
     */
    @Select("<script>" +
            "SELECT pay_price FROM trip_order " +
            "WHERE trip_id IN " +
            "<foreach collection='tripIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            "  AND created_at &gt;= DATE_SUB(NOW(), INTERVAL #{days} DAY) " +
            "  AND status IN ('PAID','BOARDED','COMPLETED','REVIEWED','REFUNDED')" +
            "</script>")
    List<java.math.BigDecimal> selectPayPrices(@Param("tripIds") List<Long> tripIds,
                                               @Param("days") int days);
}
