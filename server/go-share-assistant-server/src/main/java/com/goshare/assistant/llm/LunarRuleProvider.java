package com.goshare.assistant.llm;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.regex.Pattern;

/**
 * 农历意图规则提供器（零模型调用·真实历法）：
 * 识别「农历/阴历/老黄历/几月初几」问句，直接返回离线计算的真实农历；
 * 未命中返回 null，回落到工具/模型关卡。
 */
@Component
@RequiredArgsConstructor
public class LunarRuleProvider {

    /** 农历意图关键词（避免误吞「农历年前后优惠」等非历法问法；「农历/黄历」后紧跟「年」不命中） */
    private static final Pattern LUNAR = Pattern.compile("农历(?!年)|阴历|老黄历|黄历(?!年)|几月初几");

    private final LunarService lunarService;

    public String maybeAnswer(String question) {
        if (question == null || !LUNAR.matcher(question).find()) {
            return null;
        }
        String text = lunarService.lunarText(LocalDate.now());
        return text == null ? null : "今天是" + text + "。";
    }
}
