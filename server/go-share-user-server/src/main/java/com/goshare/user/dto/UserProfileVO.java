package com.goshare.user.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 他人可见的公开资料（脱敏）。
 *
 * <p>为什么单独一个 VO：原来的 {@code GET /api/user/{id}} 直接返回 {@link UserVO}，
 * 里面有手机号、真名、实名状态——任何登录用户遍历 ID 就能批量拉走全站用户手机号（实测可复现）。
 *
 * <p>顺风车的场景确实需要"看到车主是谁"（否则用户不敢上车），但需要的是
 * 昵称/头像/信任分/是否实名认证这几项，手机号和真名不在其中。
 * 所以拆成两个接口：{@code /api/user/{id}} 收为内部命令，公开信息走 {@code /api/user/{id}/profile}。
 */
@Data
public class UserProfileVO {

    private Long id;

    private String nickname;

    private String avatarUrl;

    /** 信任分 0-5（撮合排序与车主卡片展示用） */
    private BigDecimal trustScore;

    /** 是否通过实名认证（只暴露布尔，不暴露真名） */
    private Boolean realNameVerified;

    /** 是否认证车主（决定能否发布行程） */
    private Boolean driverVerified;

    /** 注册时间，用于展示"加入 GoShare X 天" */
    private LocalDateTime joinedAt;
}
