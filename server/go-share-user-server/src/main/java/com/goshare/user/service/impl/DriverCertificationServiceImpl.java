package com.goshare.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.goshare.common.exception.BusinessException;
import com.goshare.user.dto.DriverCertificationRequest;
import com.goshare.user.dto.DriverCertificationVO;
import com.goshare.user.entity.DriverCertification;
import com.goshare.user.entity.User;
import com.goshare.user.mapper.DriverCertificationMapper;
import com.goshare.user.mapper.UserMapper;
import com.goshare.user.service.DriverCertificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 车主认证服务实现
 */
@Service
@RequiredArgsConstructor
public class DriverCertificationServiceImpl implements DriverCertificationService {

    private final DriverCertificationMapper certificationMapper;
    private final UserMapper userMapper;

    @Override
    public DriverCertificationVO apply(Long userId, DriverCertificationRequest request) {
        Long exist = certificationMapper.selectCount(
                new LambdaQueryWrapper<DriverCertification>()
                        .eq(DriverCertification::getUserId, userId));
        if (exist > 0) {
            throw new BusinessException(400, "已提交过车主认证，请勿重复申请");
        }
        DriverCertification cert = new DriverCertification();
        cert.setUserId(userId);
        cert.setLicensePlate(request.getLicensePlate());
        cert.setVehicleModel(request.getVehicleModel());
        cert.setSeatCount(request.getSeatCount());
        cert.setDrivingLicenseNo(request.getDrivingLicenseNo());
        cert.setStatus(0);
        certificationMapper.insert(cert);
        return toVO(cert);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DriverCertificationVO audit(Long certificationId, boolean pass, String remark) {
        DriverCertification cert = certificationMapper.selectById(certificationId);
        if (cert == null) {
            throw new BusinessException(404, "认证记录不存在");
        }
        if (pass) {
            cert.setStatus(1);
            // 通过认证：用户标记为车主
            User user = userMapper.selectById(cert.getUserId());
            if (user != null) {
                user.setDriverVerified(1);
                user.setRole("DRIVER");
                userMapper.updateById(user);
            }
        } else {
            cert.setStatus(2);
        }
        cert.setAuditRemark(remark);
        certificationMapper.updateById(cert);
        return toVO(cert);
    }

    @Override
    public List<DriverCertificationVO> listByUser(Long userId) {
        return certificationMapper.selectList(
                        new LambdaQueryWrapper<DriverCertification>()
                                .eq(DriverCertification::getUserId, userId)
                                .orderByDesc(DriverCertification::getCreatedAt))
                .stream().map(this::toVO).collect(Collectors.toList());
    }

    private DriverCertificationVO toVO(DriverCertification cert) {
        DriverCertificationVO vo = new DriverCertificationVO();
        BeanUtils.copyProperties(cert, vo);
        return vo;
    }
}
