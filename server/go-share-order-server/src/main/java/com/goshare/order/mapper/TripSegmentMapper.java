package com.goshare.order.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 区间库存数据访问（阶段1「换骨」：区间覆盖计数）
 *
 * <p>座位只在订单覆盖的区间（fromSeq ≤ seg.from_seq < seg.to_seq ≤ toSeq）内扣减，
 * 而非全程扣减。例如「北京→济南→上海」，乘客买「北京→济南」只扣第 1 段，
 * 「济南→上海」仍可售。
 *
 * <p>扣减安全：先 {@link #minRemainSeats} 查覆盖区间的最小余座，不足则直接失败；
 * 再 {@link #deductSegment} 条件扣减（remain_seats ≥ num），并校验影响行数等于覆盖区间数。
 * 全程在事务内调用，任何一段余座不足都会整体回滚。
 *
 * <p>注意：MySQL 不允许 UPDATE 同一张表时在子查询 FROM 里再引用它
 * （"You can't specify target table for update in FROM clause"），
 * 所以这里拆成「先查最小余座 + 再条件扣减」两步，而不是单条 NOT EXISTS。
 */
public interface TripSegmentMapper {

    /**
     * 查询订单覆盖区间的最小余座。
     * 返回 null 表示无覆盖区间（该行程无区间数据，应退回全程标量扣减）。
     */
    @Select("SELECT MIN(remain_seats) FROM trip_segment " +
            "WHERE trip_id = #{tripId} AND from_seq >= #{fromSeq} AND to_seq <= #{toSeq}")
    Integer minRemainSeats(@Param("tripId") Long tripId,
                           @Param("fromSeq") Integer fromSeq,
                           @Param("toSeq") Integer toSeq);

    /**
     * 条件扣减区间库存：仅当覆盖区间的余座都 ≥ num 才扣减（单条 UPDATE，无子查询）。
     * 返回影响行数（应等于覆盖区间数）。
     */
    @Update("UPDATE trip_segment SET remain_seats = remain_seats - #{num} " +
            "WHERE trip_id = #{tripId} " +
            "  AND from_seq >= #{fromSeq} AND to_seq <= #{toSeq} " +
            "  AND remain_seats >= #{num}")
    int deductSegment(@Param("tripId") Long tripId,
                      @Param("fromSeq") Integer fromSeq,
                      @Param("toSeq") Integer toSeq,
                      @Param("num") Integer num);

    /**
     * 条件回补区间库存：不超过总座位数才回补。
     */
    @Update("UPDATE trip_segment SET remain_seats = remain_seats + #{num} " +
            "WHERE trip_id = #{tripId} " +
            "  AND from_seq >= #{fromSeq} AND to_seq <= #{toSeq} " +
            "  AND remain_seats + #{num} <= total_seats")
    int restoreSegment(@Param("tripId") Long tripId,
                       @Param("fromSeq") Integer fromSeq,
                       @Param("toSeq") Integer toSeq,
                       @Param("num") Integer num);

    /** 查询订单覆盖的区间数（用于校验扣减影响行数） */
    @Select("SELECT COUNT(*) FROM trip_segment " +
            "WHERE trip_id = #{tripId} AND from_seq >= #{fromSeq} AND to_seq <= #{toSeq}")
    int countCoveredSegments(@Param("tripId") Long tripId,
                             @Param("fromSeq") Integer fromSeq,
                             @Param("toSeq") Integer toSeq);

    /**
     * 查询订单覆盖的区间 [from_seq, to_seq] 列表（按 from_seq 排序）。
     * 返回形如 [[0,1],[1,2]]；用于构造 Redis per-segment key 做多 key 原子扣减。
     */
    @Select("SELECT from_seq, to_seq FROM trip_segment " +
            "WHERE trip_id = #{tripId} AND from_seq >= #{fromSeq} AND to_seq <= #{toSeq} " +
            "ORDER BY from_seq")
    java.util.List<java.util.Map<String, Integer>> selectCoveredSegments(@Param("tripId") Long tripId,
                                                                         @Param("fromSeq") Integer fromSeq,
                                                                         @Param("toSeq") Integer toSeq);
}
