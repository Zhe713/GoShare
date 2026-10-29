package com.goshare.marketing.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.goshare.common.exception.BusinessException;
import com.goshare.marketing.calculator.CouponCalculator;
import com.goshare.marketing.dto.CouponVO;
import com.goshare.marketing.dto.SettleResult;
import com.goshare.marketing.entity.CouponLog;
import com.goshare.marketing.entity.CouponTemplate;
import com.goshare.marketing.entity.UserCoupon;
import com.goshare.marketing.mapper.CouponLogMapper;
import com.goshare.marketing.mapper.CouponTemplateMapper;
import com.goshare.marketing.mapper.UserCouponMapper;
import com.goshare.marketing.service.CouponService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 券生命周期服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponServiceImpl implements CouponService {

    /** Redis 秒杀库存键前缀 */
    private static final String STOCK_KEY_PREFIX = "coupon:stock:";

    /** 秒杀原子扣减脚本：1 成功，0 已抢光（不限量模板无库存键则直接成功） */
    private static final String SECKILL_LUA =
            "local cur = tonumber(redis.call('get', KEYS[1])) " +
            "if not cur then return 1 end " +
            "if cur <= 0 then return 0 end " +
            "redis.call('decrby', KEYS[1], 1) " +
            "return 1";

    private final CouponTemplateMapper templateMapper;
    private final UserCouponMapper userCouponMapper;
    private final CouponLogMapper couponLogMapper;
    private final StringRedisTemplate redisTemplate;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CouponVO receive(Long userId, Long templateId) {
        // 1. 校验模板：上架中、在领取时间内
        CouponTemplate template = templateMapper.selectById(templateId);
        if (template == null) {
            throw new BusinessException(404, "券模板不存在");
        }
        LocalDateTime now = LocalDateTime.now();
        if (template.getStatus() != 0 || now.isBefore(template.getStartTime()) || now.isAfter(template.getEndTime())) {
            throw new BusinessException(400, "当前不在领取时间内");
        }
        // 2. Redis 原子扣库存（秒杀闸门），抢光即拒绝
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(SECKILL_LUA, Long.class);
        Long result = redisTemplate.execute(script, List.of(STOCK_KEY_PREFIX + templateId));
        if (result == null || result == 0L) {
            throw new BusinessException(400, "手慢了，券已被抢光");
        }
        try {
            // 3. DB 条件更新已领数（防超发兜底）
            int updated = templateMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<CouponTemplate>()
                    .eq(CouponTemplate::getId, templateId)
                    .apply("received_count < total_count")
                    .setSql("received_count = received_count + 1"));
            if (updated == 0) {
                throw new BusinessException(400, "手慢了，券已被抢光");
            }
            // 4. 发券（唯一索引 uk_template_user 防重复领取）
            UserCoupon coupon = new UserCoupon();
            coupon.setCouponNo(generateCouponNo());
            coupon.setCouponTemplateId(templateId);
            coupon.setUserId(userId);
            coupon.setType(template.getType());
            coupon.setThreshold(template.getThreshold());
            coupon.setDiscountValue(template.getDiscountValue());
            coupon.setStatus(0);
            coupon.setReceivedAt(now);
            coupon.setExpireAt(now.plusDays(template.getExpireDays()));
            userCouponMapper.insert(coupon);
            // 5. 写领取流水
            saveLog(userId, coupon.getId(), "RECEIVE", null, BigDecimal.ZERO, "领取成功");
            return toVO(coupon);
        } catch (DuplicateKeyException e) {
            // 重复领取：本请求已扣秒杀库存但没发券，回补 Redis 保持一致
            redisTemplate.opsForValue().increment(STOCK_KEY_PREFIX + templateId);
            throw new BusinessException(400, "每人限领一张，请勿重复领取");
        } catch (Exception e) {
            // 失败回补 Redis 库存，避免扣了数却没发券
            redisTemplate.opsForValue().increment(STOCK_KEY_PREFIX + templateId);
            throw e;
        }
    }

    @Override
    public List<CouponVO> listMine(Long userId, Integer status) {
        LambdaQueryWrapper<UserCoupon> wrapper = new LambdaQueryWrapper<UserCoupon>()
                .eq(UserCoupon::getUserId, userId)
                .orderByDesc(UserCoupon::getCreatedAt);
        if (status != null) {
            wrapper.eq(UserCoupon::getStatus, status);
        }
        return userCouponMapper.selectList(wrapper).stream()
                .map(this::toVO).collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SettleResult settle(Long userId, Long couponId, String orderNo, BigDecimal totalPrice) {
        UserCoupon coupon = userCouponMapper.selectById(couponId);
        if (coupon == null || !coupon.getUserId().equals(userId)) {
            throw new BusinessException(400, "券不存在或不属于当前用户");
        }
        // 1. 条件锁定：未用 + 未过期才能锁（返回行数 0 说明不可用）
        if (userCouponMapper.lockCoupon(couponId, userId, orderNo) == 0) {
            throw new BusinessException(400, "券不可用（未使用或已过期）");
        }
        // 2. 纯 Java 算抵扣
        BigDecimal discount = CouponCalculator.calcDiscount(
                coupon.getType(), coupon.getThreshold(), coupon.getDiscountValue(), totalPrice);
        BigDecimal payPrice = totalPrice.subtract(discount);
        saveLog(userId, couponId, "LOCK", orderNo, discount, "下单锁定，抵扣 " + discount + " 元");
        return new SettleResult(discount, payPrice);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirmUse(Long userId, Long couponId, String orderNo) {
        UserCoupon coupon = userCouponMapper.selectById(couponId);
        if (coupon == null) {
            return; // 券不存在，忽略（内部回调，尽力而为）
        }
        // 已确认过则幂等返回
        if (coupon.getStatus() == 1 && orderNo.equals(coupon.getOrderNo())) {
            return;
        }
        if (userCouponMapper.useCoupon(couponId, userId, orderNo) == 0) {
            throw new BusinessException(400, "券状态异常，无法确认使用");
        }
        saveLog(userId, couponId, "USE", orderNo, coupon.getDiscountValue(), "支付成功，正式用券");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void returnCoupon(Long userId, Long couponId, String orderNo) {
        UserCoupon coupon = userCouponMapper.selectById(couponId);
        if (coupon == null) {
            return;
        }
        // 已退回则幂等返回
        if (coupon.getStatus() == 0) {
            return;
        }
        if (userCouponMapper.returnCoupon(couponId, userId, orderNo) == 0) {
            throw new BusinessException(400, "券状态异常，无法退回");
        }
        saveLog(userId, couponId, "RETURN", orderNo, BigDecimal.ZERO, "订单取消，券已退回");
    }

    // ---------- 私有方法 ----------

    private void saveLog(Long userId, Long couponId, String action, String orderNo,
                         BigDecimal amount, String remark) {
        CouponLog logRecord = new CouponLog();
        logRecord.setUserId(userId);
        logRecord.setCouponId(couponId);
        logRecord.setAction(action);
        logRecord.setOrderNo(orderNo);
        logRecord.setAmount(amount);
        logRecord.setRemark(remark);
        couponLogMapper.insert(logRecord);
    }

    /** 券号：C + yyyyMMddHHmmss + 6位随机数字 */
    private String generateCouponNo() {
        return "C" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
    }

    private CouponVO toVO(UserCoupon coupon) {
        CouponVO vo = new CouponVO();
        vo.setId(coupon.getId());
        vo.setCouponNo(coupon.getCouponNo());
        vo.setCouponTemplateId(coupon.getCouponTemplateId());
        vo.setType(coupon.getType());
        vo.setThreshold(coupon.getThreshold());
        vo.setDiscountValue(coupon.getDiscountValue());
        vo.setStatus(coupon.getStatus());
        vo.setOrderNo(coupon.getOrderNo());
        vo.setReceivedAt(coupon.getReceivedAt());
        vo.setUsedAt(coupon.getUsedAt());
        vo.setExpireAt(coupon.getExpireAt());
        return vo;
    }
}
