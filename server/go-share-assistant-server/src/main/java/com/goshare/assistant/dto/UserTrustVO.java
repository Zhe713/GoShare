package com.goshare.assistant.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * user 服务返回的用户信任分（assistant 侧解析用）
 */
@Data
public class UserTrustVO {

    private Long id;

    private BigDecimal trustScore;
}
