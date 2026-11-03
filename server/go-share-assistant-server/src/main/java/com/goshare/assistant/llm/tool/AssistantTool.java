package com.goshare.assistant.llm.tool;

import java.util.Map;

/**
 * AI 助手工具抽象（Function Calling 取数通道）：
 * 模型只能通过工具获取真实数据（行程/天气/农历/时间），工具返回空或失败时由模型如实告知用户，
 * 从根本上杜绝"确定性知识无数据源裸生成"类幻觉。
 */
public interface AssistantTool {

    /** 工具名（模型调用时使用的英文标识） */
    String name();

    /** 工具用途描述（注入模型，决定模型何时调用） */
    String description();

    /**
     * OpenAI 兼容 JSON Schema 参数描述（qwen Function Calling 直接透传）
     * 返回形如 {"type":"object","properties":{...},"required":[...]}
     */
    Map<String, Object> parameters();

    /**
     * 执行工具并返回真实数据文本；参数为模型传来的 JSON 字符串。
     * 失败或无可查数据时返回说明性文本（模型会按约束如实转述，不编造）。
     */
    String execute(String argumentsJson);
}
