package com.goshare.assistant.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 农历规则提供器：识别农历/阴历问句，返回真实历法；不误吞行程/时间问法
 */
class LunarRuleProviderTest {

    private final LunarRuleProvider provider = new LunarRuleProvider(new LunarService());

    @Test
    void lunarQuestionReturnsRealLunar() {
        String answer = provider.maybeAnswer("农历");
        assertNotNull(answer);
        assertTrue(answer.startsWith("今天是农历"));
        assertTrue(answer.contains("年"));
        assertTrue(answer.endsWith("。"));
    }

    @Test
    void yinliQuestionAlsoHit() {
        assertNotNull(provider.maybeAnswer("今天是阴历几号"));
        assertNotNull(provider.maybeAnswer("老黄历看看"));
    }

    @Test
    void nonLunarQuestionReturnsNull() {
        assertNull(provider.maybeAnswer("北京到邯郸有车吗"));
        assertNull(provider.maybeAnswer("现在几点了"));
        assertNull(provider.maybeAnswer("明天天气怎么样"));
        assertNull(provider.maybeAnswer("农历年前后有优惠吗"));
    }
}
