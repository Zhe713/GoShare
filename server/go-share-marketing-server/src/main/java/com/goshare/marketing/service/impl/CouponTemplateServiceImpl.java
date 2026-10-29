package com.goshare.marketing.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.goshare.common.exception.BusinessException;
import com.goshare.marketing.dto.TemplateCreateRequest;
import com.goshare.marketing.entity.CouponTemplate;
import com.goshare.marketing.mapper.CouponTemplateMapper;
import com.goshare.marketing.service.CouponTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 券模板服务实现
 */
@Service
@RequiredArgsConstructor
public class CouponTemplateServiceImpl implements CouponTemplateService {

    /** Redis 秒杀库存键前缀：coupon:stock:{模板ID} */
    private static final String STOCK_KEY_PREFIX = "coupon:stock:";

    private final CouponTemplateMapper templateMapper;
    private final StringRedisTemplate redisTemplate;

    @Override
    public CouponTemplate create(TemplateCreateRequest request) {
        if (!request.getEndTime().isAfter(request.getStartTime())) {
            throw new BusinessException(400, "领取结束时间必须晚于开始时间");
        }
        CouponTemplate template = new CouponTemplate();
        template.setTemplateName(request.getTemplateName());
        template.setType(request.getType());
        template.setThreshold(request.getThreshold());
        template.setDiscountValue(request.getDiscountValue());
        template.setTotalCount(request.getTotalCount());
        template.setReceivedCount(0);
        template.setStartTime(request.getStartTime());
        template.setEndTime(request.getEndTime());
        template.setExpireDays(request.getExpireDays() == null ? 7 : request.getExpireDays());
        template.setStatus(0);
        template.setRemark(request.getRemark());
        templateMapper.insert(template);
        // 初始化秒杀 Redis 库存（不限量模板不初始化）
        if (template.getTotalCount() != null && template.getTotalCount() > 0) {
            redisTemplate.opsForValue().set(STOCK_KEY_PREFIX + template.getId(),
                    String.valueOf(template.getTotalCount()));
        }
        return template;
    }

    @Override
    public List<CouponTemplate> listOnSale() {
        return templateMapper.selectList(new LambdaQueryWrapper<CouponTemplate>()
                .eq(CouponTemplate::getStatus, 0)
                .le(CouponTemplate::getStartTime, LocalDateTime.now())
                .ge(CouponTemplate::getEndTime, LocalDateTime.now())
                .orderByDesc(CouponTemplate::getCreatedAt));
    }
}
