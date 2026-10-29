package com.goshare.user.service;

import com.goshare.user.dto.DriverCertificationRequest;
import com.goshare.user.dto.DriverCertificationVO;

import java.util.List;

/**
 * 车主认证服务
 */
public interface DriverCertificationService {

    /** 提交车主认证申请 */
    DriverCertificationVO apply(Long userId, DriverCertificationRequest request);

    /** 审核（管理端）：通过则把用户标记为车主 */
    DriverCertificationVO audit(Long certificationId, boolean pass, String remark);

    /** 查询当前用户的认证记录 */
    List<DriverCertificationVO> listByUser(Long userId);
}
