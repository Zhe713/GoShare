package com.goshare.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 幂等记录实体（对应表 idempotency_key）：防重复下单/重复回调
 */
@Data
@TableName("idempotency_key")
public class IdempotencyRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 幂等键（业务类型+业务号） */
    private String idempotentKey;

    /** 业务类型 CREATE_ORDER / PAY_CALLBACK */
    private String bizType;

    /** 首次处理的响应结果（重复请求直接返回） */
    private String responseJson;

    /** 过期时间 */
    private LocalDateTime expireAt;

    private LocalDateTime createdAt;
}
