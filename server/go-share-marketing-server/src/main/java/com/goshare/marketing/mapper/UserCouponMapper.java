package com.goshare.marketing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.goshare.marketing.entity.UserCoupon;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 用户券数据访问
 */
public interface UserCouponMapper extends BaseMapper<UserCoupon> {

    /** 条件锁定：只有未用且未过期的券才能被锁定占用 */
    @Update("UPDATE user_coupon SET status = 3, order_no = #{orderNo} " +
            "WHERE id = #{couponId} AND user_id = #{userId} AND status = 0 AND expire_at > NOW()")
    int lockCoupon(@Param("couponId") Long couponId, @Param("userId") Long userId,
                   @Param("orderNo") String orderNo);

    /** 确认用券：只有锁定中的券才能确认使用 */
    @Update("UPDATE user_coupon SET status = 1, used_at = NOW() " +
            "WHERE id = #{couponId} AND user_id = #{userId} AND status = 3 AND order_no = #{orderNo}")
    int useCoupon(@Param("couponId") Long couponId, @Param("userId") Long userId,
                  @Param("orderNo") String orderNo);

    /** 退回：锁定中的券退回为未用 */
    @Update("UPDATE user_coupon SET status = 0, order_no = NULL " +
            "WHERE id = #{couponId} AND user_id = #{userId} AND status = 3 AND order_no = #{orderNo}")
    int returnCoupon(@Param("couponId") Long couponId, @Param("userId") Long userId,
                     @Param("orderNo") String orderNo);
}
