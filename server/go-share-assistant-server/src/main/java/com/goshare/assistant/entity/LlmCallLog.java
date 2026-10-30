package com.goshare.assistant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * LLM 调用记账表：记录每次模型调用/缓存命中的成本，用于演示省 token 四关卡的效果
 */
@Data
@TableName("llm_call_log")
public class LlmCallLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务类型：A1语义搜索 / B1工单摘要 / A2取消挽留 / FAQ天气 */
    private String bizType;

    /** 实际使用的模型：mock / 小模型 / 大模型 */
    private String modelName;

    /** 固定前缀+问题 的哈希（答案缓存键） */
    private String promptHash;

    /** 输入 token 数（mock 按字数模拟） */
    private Integer inputTokens;

    /** 输出 token 数 */
    private Integer outputTokens;

    /** 耗时（毫秒） */
    private Integer durationMs;

    /** 是否命中答案缓存 0否 1是 */
    private Integer cacheHit;

    /** 是否被关卡拦截未调大模型（固定前缀/小模型先答）0否 1是 */
    private Integer gateSkipped;

    private LocalDateTime createdAt;
}
