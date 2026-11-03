package com.goshare.assistant.llm;

import java.util.List;
import java.util.Map;

/**
 * LLM 客户端抽象：可插拔（Mock 零成本 / HTTP 接真实 API）
 * 设计约束：金额、行程列表等业务数据绝不由此生成，只生成话术/摘要/解释文案；
 * 工具聊天通道（chat）供 Function Calling 取数：模型只能通过工具拿真实数据，无工具不答。
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

    /**
     * 工具聊天通道（Function Calling）：多角色消息 + 工具描述，模型可发起工具调用。
     * 调用方负责执行工具并回填 tool 消息后再次调用，直至模型给出最终文本。
     *
     * @param messages 多角色消息（system/user/assistant/tool），含必要的历史上下文
     * @param tools    工具描述（OpenAI 兼容 format）
     * @param tier     模型档位
     * @return 文本 + 工具调用列表（若模型选择调用工具）
     */
    ToolChatResponse chat(List<ToolMessage> messages, List<Map<String, Object>> tools, Tier tier);

    /** 生成结果：文本 + 模拟/实际 token 数 */
    record LlmResponse(String text, int inputTokens, int outputTokens) {
    }

    /** 多角色消息（Function Calling 通道专用）：role=system|user|assistant|tool */
    record ToolMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId) {

        public ToolMessage(String role, String content) {
            this(role, content, null, null);
        }

        /** assistant 携带工具调用（回填给模型的 assistant 消息） */
        public static ToolMessage assistantToolCalls(List<ToolCall> calls) {
            return new ToolMessage("assistant", null, calls, null);
        }

        /** 工具执行结果（tool 消息，须带调用 id 关联） */
        public static ToolMessage toolResult(String callId, String content) {
            return new ToolMessage("tool", content, null, callId);
        }
    }

    /** 模型发起的工具调用 */
    record ToolCall(String id, String name, String arguments) {
    }

    /** 工具聊天响应：最终文本 + 工具调用列表 + token */
    record ToolChatResponse(String text, List<ToolCall> toolCalls, int inputTokens, int outputTokens) {

        public ToolChatResponse(String text, int inputTokens, int outputTokens) {
            this(text, List.of(), inputTokens, outputTokens);
        }
    }
}

