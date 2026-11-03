package com.goshare.assistant.llm.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表：维护模型可用的真实取数工具集（OpenAI 兼容 Function Calling 描述），
 * 并按工具名派发执行。所有工具均以真实数据源作答；执行失败返回说明文本，由模型如实转述。
 */
@Slf4j
@Component
public class ToolRegistry {

    /** Spring 注入全部 AssistantTool 实现（固定顺序：行程/天气/农历/时间） */
    private final List<AssistantTool> tools;

    public ToolRegistry(List<AssistantTool> tools) {
        this.tools = tools;
    }

    /** 全部工具描述（qwen Function Calling 透传格式） */
    public List<Map<String, Object>> toOpenAiTools() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (AssistantTool tool : tools) {
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", tool.name());
            fn.put("description", tool.description());
            fn.put("parameters", tool.parameters());
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("type", "function");
            wrapper.put("function", fn);
            result.add(wrapper);
        }
        return result;
    }

    /**
     * 执行工具；未注册或执行异常返回失败说明（模型按「无工具不答」约束如实转述）。
     */
    public String execute(String name, String argumentsJson) {
        for (AssistantTool tool : tools) {
            if (tool.name().equals(name)) {
                try {
                    return tool.execute(argumentsJson);
                } catch (Exception e) {
                    log.warn("[tool] 执行失败 name={} err={}", name, e.getMessage());
                    return "查询失败：" + e.getMessage();
                }
            }
        }
        log.warn("[tool] 未注册工具 name={}", name);
        return "没有找到可用的工具来回答这个问题。";
    }
}
