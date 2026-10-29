package com.goshare.order.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 行程座位余量数据访问（跨服务操作行程表：条件扣减/回补，防超卖）
 */
public interface TripSeatMapper {

    /** 条件扣减：余座充足且行程可预订才扣，返回影响行数 */
    @Update("UPDATE trip SET remain_seats = remain_seats - #{num} " +
            "WHERE id = #{tripId} AND remain_seats >= #{num} AND status IN (0, 1)")
    int deductSeat(@Param("tripId") Long tripId, @Param("num") Integer num);

    /** 条件回补：不超过总座位数才回补 */
    @Update("UPDATE trip SET remain_seats = remain_seats + #{num} " +
            "WHERE id = #{tripId} AND remain_seats + #{num} <= total_seats")
    int restoreSeat(@Param("tripId") Long tripId, @Param("num") Integer num);
}
