package com.goshare.marketing.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.goshare.common.exception.BusinessException;
import com.goshare.marketing.dto.SettleResult;
import com.goshare.marketing.entity.CouponTemplate;
import com.goshare.marketing.entity.UserCoupon;
import com.goshare.marketing.mapper.CouponLogMapper;
import com.goshare.marketing.mapper.CouponTemplateMapper;
import com.goshare.marketing.mapper.UserCouponMapper;
import com.goshare.marketing.service.CouponService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 券生命周期单元测试：领券/秒杀/用券/退券
 */
@ExtendWith(MockitoExtension.class)
class CouponServiceTest {

    @Mock
    private CouponTemplateMapper templateMapper;
    @Mock
    private UserCouponMapper userCouponMapper;
    @Mock
    private CouponLogMapper couponLogMapper;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private CouponService couponService;

    @BeforeEach
    void setUp() {
        couponService = new CouponServiceImpl(templateMapper, userCouponMapper,
                couponLogMapper, redisTemplate);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    private CouponTemplate onSaleTemplate(int totalCount) {
        CouponTemplate t = new CouponTemplate();
        t.setId(1L);
        t.setStatus(0);
        t.setStartTime(LocalDateTime.now().minusHours(1));
        t.setEndTime(LocalDateTime.now().plusDays(1));
        t.setTotalCount(totalCount);
        t.setExpireDays(7);
        return t;
    }

    @Test
    @DisplayName("手慢抢光领不到券")
    void seckillSoldOut() {
        when(templateMapper.selectById(1L)).thenReturn(onSaleTemplate(10));
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(0L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> couponService.receive(100L, 1L));
        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("同一用户重复领同一张券被拒（唯一索引兜底）")
    void duplicateReceiveRejected() {
        when(templateMapper.selectById(1L)).thenReturn(onSaleTemplate(10));
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L);
        when(templateMapper.update(any(), any())).thenReturn(1);
        when(userCouponMapper.insert(any(UserCoupon.class)))
                .thenThrow(new DuplicateKeyException("uk_template_user"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> couponService.receive(100L, 1L));
        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("别人的券不能用")
    void cannotUseOthersCoupon() {
        UserCoupon coupon = new UserCoupon();
        coupon.setId(9L);
        coupon.setUserId(2L); // 属于用户2
        when(userCouponMapper.selectById(9L)).thenReturn(coupon);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> couponService.settle(1L, 9L, "ORD1", new BigDecimal("100")));
        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("用过的券不能再锁定")
    void usedCouponCannotLock() {
        UserCoupon coupon = new UserCoupon();
        coupon.setId(9L);
        coupon.setUserId(1L);
        when(userCouponMapper.selectById(9L)).thenReturn(coupon);
        when(userCouponMapper.lockCoupon(9L, 1L, "ORD1")).thenReturn(0); // 条件更新失败
        BusinessException ex = assertThrows(BusinessException.class,
                () -> couponService.settle(1L, 9L, "ORD1", new BigDecimal("100")));
        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("下单锁定：满减券正确抵扣并算出实付")
    void settleWithFullReduction() {
        UserCoupon coupon = new UserCoupon();
        coupon.setId(9L);
        coupon.setUserId(1L);
        coupon.setType("FULL_REDUCTION");
        coupon.setThreshold(new BigDecimal("50"));
        coupon.setDiscountValue(new BigDecimal("10"));
        when(userCouponMapper.selectById(9L)).thenReturn(coupon);
        when(userCouponMapper.lockCoupon(9L, 1L, "ORD1")).thenReturn(1);
        SettleResult result = couponService.settle(1L, 9L, "ORD1", new BigDecimal("60"));
        assertEquals(0, result.getDiscount().compareTo(new BigDecimal("10")));
        assertEquals(0, result.getPayPrice().compareTo(new BigDecimal("50")));
    }

    @Test
    @DisplayName("支付成功正式用券（锁定转已用）")
    void confirmUseAfterPay() {
        when(userCouponMapper.selectById(9L)).thenReturn(couponOfStatus(3, 1L, "ORD1"));
        when(userCouponMapper.useCoupon(9L, 1L, "ORD1")).thenReturn(1);
        couponService.confirmUse(1L, 9L, "ORD1"); // 不抛异常即通过
    }

    @Test
    @DisplayName("重复回调确认用券是幂等的，不报错")
    void confirmUseIdempotent() {
        when(userCouponMapper.selectById(9L)).thenReturn(couponOfStatus(1, 1L, "ORD1"));
        couponService.confirmUse(1L, 9L, "ORD1"); // 已用且同订单 → 幂等返回
    }

    @Test
    @DisplayName("取消订单券退回未用")
    void returnCouponAfterCancel() {
        when(userCouponMapper.selectById(9L)).thenReturn(couponOfStatus(3, 1L, "ORD1"));
        when(userCouponMapper.returnCoupon(9L, 1L, "ORD1")).thenReturn(1);
        couponService.returnCoupon(1L, 9L, "ORD1"); // 不抛异常即通过
    }

    private UserCoupon couponOfStatus(int status, Long userId, String orderNo) {
        UserCoupon coupon = new UserCoupon();
        coupon.setId(9L);
        coupon.setUserId(userId);
        coupon.setStatus(status);
        coupon.setOrderNo(orderNo);
        return coupon;
    }
}
