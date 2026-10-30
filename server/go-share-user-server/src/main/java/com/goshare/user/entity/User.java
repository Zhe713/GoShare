package com.goshare.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户实体（对应表 user）
 */
@Data
@TableName("`user`")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 手机号 */
    private String phone;

    /** 密码哈希（BCrypt） */
    private String passwordHash;

    /** 昵称 */
    private String nickname;

    /** 头像地址 */
    private String avatarUrl;

    /** 真实姓名 */
    private String realName;

    /** 身份证号 */
    private String idCardNo;

    /** 是否实名认证 0否 1是 */
    private Integer realNameVerified;

    /** 是否车主认证 0否 1是 */
    private Integer driverVerified;

    /** 角色 PASSENGER / DRIVER / ADMIN */
    private String role;

    /** 状态 1正常 0禁用 */
    private Integer status;

    /** 车主信任分 0-5（A1 撮合排序特征） */
    private java.math.BigDecimal trustScore;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
