package com.goshare.assistant.llm.tool;

import com.goshare.assistant.llm.TimeRuleProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 实时时间工具：返回服务器当前时刻（真实数据，不缓存），供模型回答「现在几点了」类问题。
 */
@Component
@RequiredArgsConstructor
public class CurrentTimeTool implements AssistantTool {

    private final TimeRuleProvider timeRuleProvider;

    @Override
    public String name() {
        return "get_current_time";
    }

    @Override
    public String description() {
        return "获取当前实时时间（年月日时分）。当用户询问现在几点、当前时间、现在什么时间时调用。";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of("type", "object", "properties", Map.of(), "required", new String[]{});
    }

    @Override
    public String execute(String argumentsJson) {
        String answer = timeRuleProvider.maybeAnswer("现在几点了");
        return answer == null ? "时间服务暂时不可用。" : answer;
    }
}
