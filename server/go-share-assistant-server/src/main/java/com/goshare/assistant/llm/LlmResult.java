package com.goshare.assistant.llm;

import lombok.Data;

/**
 * LLM 调用结果（含成本信息，供前端/后台展示省 token 效果）
 */
@Data
public class LlmResult {

    /** 生成文本 */
    private String text;

    /** 是否命中答案缓存（关卡1，零模型调用） */
    private boolean fromCache;

    /** 实际使用的模型名：mock / 小模型 / 大模型 */
    private String modelName;

    /** 是否被关卡拦截未调大模型（固定前缀意图走规则/小模型） */
    private boolean gateSkipped;

    /** 输入 token */
    private int inputTokens;

    /** 输出 token */
    private int outputTokens;

    /** 耗时（毫秒） */
    private int durationMs;

    public static LlmResult of(String text, boolean fromCache, String modelName,
                               boolean gateSkipped, int inputTokens, int outputTokens, int durationMs) {
        LlmResult r = new LlmResult();
        r.text = text;
        r.fromCache = fromCache;
        r.modelName = modelName;
        r.gateSkipped = gateSkipped;
        r.inputTokens = inputTokens;
        r.outputTokens = outputTokens;
        r.durationMs = durationMs;
        return r;
    }
}
