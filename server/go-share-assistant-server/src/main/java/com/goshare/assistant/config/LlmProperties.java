package com.goshare.assistant.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LLM 接入配置：可插拔（mock 默认 / http 接真实 API）
 */
@Data
@Component
@ConfigurationProperties(prefix = "goshare.llm")
public class LlmProperties {

    /** 接入方式：mock（默认）| http */
    private String mode = "mock";

    /** 真实模型 HTTP 地址（mode=http 时生效） */
    private String httpUrl;

    /** 模型 API Key */
    private String apiKey;

    /** 小模型档（规则意图/轻量问答，默认 qwen-plus） */
    private String model = "qwen-plus";

    /** 大模型档（生成类，默认 qwen-max） */
    private String modelLarge = "qwen-max";

    /** 数据版本号：行程价格/余座/券规则变化时 +1，旧答案缓存自动作废 */
    private int dataVersion = 1;

    /** 固定前缀校验开关 */
    private boolean prefixCheck = true;
}
