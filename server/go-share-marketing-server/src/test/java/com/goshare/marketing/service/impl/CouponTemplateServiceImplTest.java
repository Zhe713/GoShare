package com.goshare.marketing.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.goshare.common.exception.BusinessException;
import com.goshare.marketing.dto.TemplateCreateRequest;
import com.goshare.marketing.entity.CouponTemplate;
import com.goshare.marketing.mapper.CouponTemplateMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 券模板：创建校验 / 限量模板 Redis 秒杀库存初始化 / 不限量不初始化 / 在售列表
 */
class CouponTemplateServiceImplTest {

    private CouponTemplateMapper templateMapper;
    private StringRedisTemplate redisTemplate;
    private CouponTemplateServiceImpl service;

    private CouponTemplateServiceImpl newService() {
        templateMapper = mock(CouponTemplateMapper.class);
        redisTemplate = mock(StringRedisTemplate.class);
        return new CouponTemplateServiceImpl(templateMapper, redisTemplate);
    }

    private TemplateCreateRequest req() {
        TemplateCreateRequest r = new TemplateCreateRequest();
        r.setTemplateName("新人立减券");
        r.setType("CASH");
        r.setThreshold(new BigDecimal("0"));
        r.setDiscountValue(new BigDecimal("5"));
        r.setTotalCount(100);
        r.setStartTime(LocalDateTime.now().minusDays(1));
        r.setEndTime(LocalDateTime.now().plusDays(7));
        return r;
    }

    @Test
    void createRejectsReversedTimeRange() {
        service = newService();
        TemplateCreateRequest r = req();
        r.setEndTime(LocalDateTime.now().minusDays(1));
        BusinessException ex = assertThrows(BusinessException.class, () -> service.create(r));
        assertEquals(400, ex.getCode());
        verify(templateMapper, never()).insert(any(CouponTemplate.class));
    }

    @Test
    void createLimitedInitializesRedisStock() {
        service = newService();
        ArgumentCaptor<CouponTemplate> captor = ArgumentCaptor.forClass(CouponTemplate.class);
        when(templateMapper.insert(captor.capture())).thenAnswer(inv -> {
            captor.getValue().setId(10L);
            return 1;
        });
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        CouponTemplate t = service.create(req());

        assertEquals(10L, t.getId());
        assertEquals(0, t.getStatus());
        // 限量模板：初始化秒杀库存
        verify(valueOps).set("coupon:stock:10", "100");
    }

    @Test
    void createUnlimitedSkipsRedisInitAndDefaultsExpireDays() {
        service = newService();
        TemplateCreateRequest r = req();
        r.setTotalCount(0);
        r.setExpireDays(null);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        CouponTemplate t = service.create(r);

        assertEquals(7, t.getExpireDays()); // 默认 7 天
        verify(valueOps, never()).set(anyString(), anyString());
    }

    @Test
    void listOnSaleReturnsActiveTemplates() {
        service = newService();
        CouponTemplate t = new CouponTemplate();
        t.setId(1L);
        t.setStatus(0);
        when(templateMapper.selectList(any(Wrapper.class))).thenReturn(List.of(t));

        List<CouponTemplate> list = service.listOnSale();

        assertEquals(1, list.size());
        assertEquals(1L, list.get(0).getId());
    }
}
