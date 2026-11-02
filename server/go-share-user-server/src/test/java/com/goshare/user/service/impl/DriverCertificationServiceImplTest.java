package com.goshare.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.goshare.common.exception.BusinessException;
import com.goshare.user.dto.DriverCertificationRequest;
import com.goshare.user.dto.DriverCertificationVO;
import com.goshare.user.entity.DriverCertification;
import com.goshare.user.entity.User;
import com.goshare.user.mapper.DriverCertificationMapper;
import com.goshare.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 车主认证：申请查重 / 审核通过置车主 / 驳回 / 我的认证列表
 */
class DriverCertificationServiceImplTest {

    private DriverCertificationMapper certMapper;
    private UserMapper userMapper;
    private DriverCertificationServiceImpl service;

    private DriverCertificationServiceImpl newService() {
        certMapper = mock(DriverCertificationMapper.class);
        userMapper = mock(UserMapper.class);
        return new DriverCertificationServiceImpl(certMapper, userMapper);
    }

    private DriverCertificationRequest req() {
        DriverCertificationRequest r = new DriverCertificationRequest();
        r.setLicensePlate("冀D12345");
        r.setVehicleModel("大众帕萨特");
        r.setSeatCount(3);
        r.setDrivingLicenseNo("130101199901011234");
        return r;
    }

    @Test
    void applyRejectsDuplicate() {
        service = newService();
        when(certMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.apply(2L, req()));
        assertEquals(400, ex.getCode());
        verify(certMapper, never()).insert(any(DriverCertification.class));
    }

    @Test
    void applySuccessInsertsPendingCert() {
        service = newService();
        when(certMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        ArgumentCaptor<DriverCertification> captor = ArgumentCaptor.forClass(DriverCertification.class);
        when(certMapper.insert(captor.capture())).thenReturn(1);

        DriverCertificationVO vo = service.apply(2L, req());

        assertEquals("冀D12345", vo.getLicensePlate());
        assertEquals(0, vo.getStatus()); // 待审核
        assertEquals("冀D12345", captor.getValue().getLicensePlate());
        assertEquals(2L, captor.getValue().getUserId());
        assertEquals("130101199901011234", captor.getValue().getDrivingLicenseNo());
    }

    @Test
    void auditNotFound() {
        service = newService();
        when(certMapper.selectById(99L)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.audit(99L, true, "资料齐全"));
        assertEquals(404, ex.getCode());
    }

    @Test
    void auditPassMarksDriverRole() {
        service = newService();
        DriverCertification cert = new DriverCertification();
        cert.setId(1L);
        cert.setUserId(2L);
        cert.setStatus(0);
        when(certMapper.selectById(1L)).thenReturn(cert);
        User user = new User();
        user.setId(2L);
        user.setDriverVerified(0);
        user.setRole("PASSENGER");
        when(userMapper.selectById(2L)).thenReturn(user);

        DriverCertificationVO vo = service.audit(1L, true, "资料齐全");

        assertEquals(1, vo.getStatus()); // 通过
        assertEquals("资料齐全", vo.getAuditRemark());
        // 用户升级为车主
        ArgumentCaptor<User> uCaptor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(uCaptor.capture());
        assertEquals(1, uCaptor.getValue().getDriverVerified());
        assertEquals("DRIVER", uCaptor.getValue().getRole());
    }

    @Test
    void auditRejectKeepsUserUnchanged() {
        service = newService();
        DriverCertification cert = new DriverCertification();
        cert.setId(1L);
        cert.setUserId(2L);
        cert.setStatus(0);
        when(certMapper.selectById(1L)).thenReturn(cert);

        DriverCertificationVO vo = service.audit(1L, false, "证件照片模糊");

        assertEquals(2, vo.getStatus()); // 拒绝
        verify(userMapper, never()).updateById(any(User.class));
        ArgumentCaptor<DriverCertification> cCaptor = ArgumentCaptor.forClass(DriverCertification.class);
        verify(certMapper).updateById(cCaptor.capture());
        assertEquals("证件照片模糊", cCaptor.getValue().getAuditRemark());
    }

    @Test
    void listByUserReturnsCertifications() {
        service = newService();
        DriverCertification cert = new DriverCertification();
        cert.setId(1L);
        cert.setUserId(2L);
        cert.setStatus(1);
        when(certMapper.selectList(any(Wrapper.class))).thenReturn(List.of(cert));

        List<DriverCertificationVO> list = service.listByUser(2L);

        assertEquals(1, list.size());
        assertEquals(1, list.get(0).getStatus());
    }
}
