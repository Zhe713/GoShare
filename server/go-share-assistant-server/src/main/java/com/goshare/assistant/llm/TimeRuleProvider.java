package com.goshare.assistant.llm;

import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 时间/日期意图规则提供器（零模型调用）：
 * 识别「现在几点了/现在几点/几点了」返回服务器实时时间；
 * 识别「今天几号/今天星期几/今天日期」返回服务器当前日期与星期；
 * 不匹配「行程几点出发」「明天去北京」等带出行上下文的问法。
 * 日期属于确定性信息，交给模型就是在给它编造的机会——一律规则回答。
 */
@Component
public class TimeRuleProvider {

    /** 明确的时间问句；「几点」单独出现才算（避免误吞「几点出发」类出行问句） */
    private static final Pattern TIME = Pattern.compile("现在(是)?几点|当前几点|几点了|几点钟(了)?|^几点$");

    /** 明确的日期问句（今天几号 / 今天星期几 / 今天日期），不匹配出行问法 */
    private static final Pattern DATE = Pattern.compile(
            "今天(是)?(几号|几月几号|什么日子|日期|周几|星期几)|今天几号|^几号$|今天星期几");

    public String maybeAnswer(String question) {
        if (DATE.matcher(question).find()) {
            LocalDate today = LocalDate.now();
            DayOfWeek dow = today.getDayOfWeek();
            String weekday = dow.getDisplayName(TextStyle.FULL, Locale.CHINA);
            return String.format("今天是%d年%d月%d日，%s。", today.getYear(), today.getMonthValue(),
                    today.getDayOfMonth(), weekday);
        }
        if (!TIME.matcher(question).find()) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        return String.format("现在是%d年%d月%d日 %02d:%02d。", now.getYear(), now.getMonthValue(),
                now.getDayOfMonth(), now.getHour(), now.getMinute());
    }
}
