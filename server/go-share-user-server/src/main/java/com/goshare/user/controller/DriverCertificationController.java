package com.goshare.user.controller;

import com.goshare.common.result.Result;
import com.goshare.user.dto.CertificationAuditRequest;
import com.goshare.user.dto.DriverCertificationRequest;
import com.goshare.user.dto.DriverCertificationVO;
import com.goshare.user.service.DriverCertificationService;
import com.goshare.user.util.CurrentUserResolver;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 车主认证接口
 */
@RestController
@RequestMapping("/api/driver")
@RequiredArgsConstructor
public class DriverCertificationController {

    private final DriverCertificationService certificationService;
    private final CurrentUserResolver currentUserResolver;

    /** 提交车主认证申请 */
    @PostMapping("/certification/apply")
    public Result<DriverCertificationVO> apply(@RequestHeader(value = "Authorization", required = false) String auth,
                                               @RequestBody @Valid DriverCertificationRequest request) {
        Long userId = currentUserResolver.resolveUserId(auth);
        return Result.success(certificationService.apply(userId, request));
    }

    /** 审核（管理端调用） */
    @PostMapping("/certification/audit")
    public Result<DriverCertificationVO> audit(@RequestBody @Valid CertificationAuditRequest request) {
        return Result.success(certificationService.audit(
                request.getCertificationId(), request.getPass(), request.getRemark()));
    }

    /** 我的认证记录 */
    @GetMapping("/certification/mine")
    public Result<List<DriverCertificationVO>> mine(@RequestHeader(value = "Authorization", required = false) String auth) {
        Long userId = currentUserResolver.resolveUserId(auth);
        return Result.success(certificationService.listByUser(userId));
    }
}
