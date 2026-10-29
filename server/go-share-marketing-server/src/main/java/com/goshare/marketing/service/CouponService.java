package com.goshare.marketing.service;

import com.goshare.marketing.dto.CouponVO;
import com.goshare.marketing.dto.SettleResult;

import java.math.BigDecimal;
import java.util.List;

/**
 * 券生命周期服务：领券、我的券、用券结算、确认用券、退券
 */
public interface CouponService {

    /** 领券（秒杀：Redis 原子计数 + DB 条件更新防超发，每人限领一张） */
    CouponVO receive(Long userId, Long templateId);

    /** 我的券列表（status 为空查全部） */
    List<CouponVO> listMine(Long userId, Integer status);

    /** 用券结算（下单时调用）：校验并锁定券，返回抵扣金额 */
    SettleResult settle(Long userId, Long couponId, String orderNo, BigDecimal totalPrice);

    /** 确认用券（支付成功回调时调用） */
    void confirmUse(Long userId, Long couponId, String orderNo);

    /** 退券（取消/超时关闭订单时调用） */
    void returnCoupon(Long userId, Long couponId, String orderNo);
}
