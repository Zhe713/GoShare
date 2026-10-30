package com.goshare.assistant.llm;

/**
 * LLM 客户端抽象：可插拔（Mock 零成本 / HTTP 接真实 API）
 * 设计约束：金额、行程列表等业务数据绝不由此生成，只生成话术/摘要/解释文案
 */
public interface LlmClient {

    /** 模型档位：SMALL 小模型（固定前缀意图，成本低）/ LARGE 大模型 */
    enum Tier { SMALL, LARGE }

    /**
     * 调用模型生成文本
     *
     * @param prompt 完整提示词（须以固定系统前缀开头，保证厂商缓存复用）
     * @param tier   模型档位
     * @return 生成结果
     */
    LlmResponse call(String prompt, Tier tier);

    /** 生成结果：文本 + 模拟/实际 token 数 */
    record LlmResponse(String text, int inputTokens, int outputTokens) {
    }
}
