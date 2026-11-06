package com.goshare.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.goshare.order.entity.TripOrder;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 订单数据访问
 */
public interface TripOrderMapper extends BaseMapper<TripOrder> {

    /**
     * F5 行程账单：按乘客聚合某月有效订单的次数/里程/花费。
     * passenger_id 是分片键，查询精准路由单片，GROUP BY/SUM 在单片内完成。
     * 里程用 IFNULL 兼容旧订单（区间化之前的订单 distance_km 为 NULL）。
     */
    @Select("SELECT COUNT(*) AS orderCount, " +
            "       COALESCE(SUM(IFNULL(distance_km, 0)), 0) AS totalDistanceKm, " +
            "       COALESCE(SUM(pay_price), 0) AS totalPay " +
            "FROM trip_order " +
            "WHERE passenger_id = #{passengerId} " +
            "  AND status IN ('PAID','BOARDED','COMPLETED','REVIEWED','REFUNDED') " +
            "  AND created_at >= #{start} AND created_at < #{end}")
    Map<String, Object> aggregateBill(@Param("passengerId") Long passengerId,
                                      @Param("start") String start,
                                      @Param("end") String end);

    /** F5 行程账单：按行程分组取最常线路（分片键路由单片，GROUP BY trip_id 单片内归并） */
    @Select("SELECT trip_id, COUNT(*) AS cnt " +
            "FROM trip_order " +
            "WHERE passenger_id = #{passengerId} " +
            "  AND status IN ('PAID','BOARDED','COMPLETED','REVIEWED','REFUNDED') " +
            "  AND created_at >= #{start} AND created_at < #{end} " +
            "GROUP BY trip_id ORDER BY cnt DESC LIMIT 3")
    List<Map<String, Object>> groupByTrip(@Param("passengerId") Long passengerId,
                                          @Param("start") String start,
                                          @Param("end") String end);

    /** F5 行程账单：查行程的城市对（trip 表单表） */
    @Select("SELECT id, from_city, to_city FROM trip WHERE id = #{tripId}")
    Map<String, Object> selectTripCities(@Param("tripId") Long tripId);
}
