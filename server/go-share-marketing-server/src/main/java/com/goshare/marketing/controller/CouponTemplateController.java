package com.goshare.marketing.controller;

import com.goshare.common.result.Result;
import com.goshare.marketing.dto.TemplateCreateRequest;
import com.goshare.marketing.entity.CouponTemplate;
import com.goshare.marketing.service.CouponTemplateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 券模板接口：创建、上架列表
 */
@RestController
@RequestMapping("/api/coupon/template")
@RequiredArgsConstructor
public class CouponTemplateController {

    private final CouponTemplateService templateService;

    /** 创建券模板（管理端） */
    @PostMapping("/create")
    public Result<CouponTemplate> create(@RequestBody @Valid TemplateCreateRequest request) {
        return Result.success(templateService.create(request));
    }

    /** 上架中的模板列表 */
    @GetMapping("/list")
    public Result<List<CouponTemplate>> list() {
        return Result.success(templateService.listOnSale());
    }
}
