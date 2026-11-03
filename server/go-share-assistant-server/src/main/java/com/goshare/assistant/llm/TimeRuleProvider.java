package com.goshare.assistant.llm;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

/**
 * 时间意图规则提供器（零模型调用）：
 * 识别「现在几点了/现在几点/几点了」返回服务器实时时间；
 * 不匹配「行程几点出发」等带出行上下文的问法。
 */
@Component
public class TimeRuleProvider {

    /** 明确的时间问句；「几点」单独出现才算（避免误吞「几点出发」类出行问句） */
    private static final Pattern TIME = Pattern.compile("现在(是)?几点|当前几点|几点了|几点钟(了)?|^几点$");

    public String maybeAnswer(String question) {
        if (!TIME.matcher(question).find()) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        return String.format("现在是%d年%d月%d日 %02d:%02d。", now.getYear(), now.getMonthValue(),
                now.getDayOfMonth(), now.getHour(), now.getMinute());
    }
}
