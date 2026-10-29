package com.goshare.marketing.service;

import com.goshare.marketing.dto.TemplateCreateRequest;
import com.goshare.marketing.entity.CouponTemplate;

import java.util.List;

/**
 * 券模板服务
 */
public interface CouponTemplateService {

    /** 创建模板（并初始化秒杀 Redis 库存） */
    CouponTemplate create(TemplateCreateRequest request);

    /** 上架中的模板列表 */
    List<CouponTemplate> listOnSale();
}
