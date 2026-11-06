package com.goshare.assistant.llm;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

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
    void springFestivalQuestionReturnsRealCountdown() {
        // 历史幻觉：模型答「2027年春节是2月1日」；规则必须给确定性倒计时 2027-02-06
        // 天数按运行日动态计算，写死「还有 95 天」会导致跨天后必然失败
        String answer = provider.maybeAnswer("离过年还有几天");
        assertNotNull(answer);
        assertTrue(answer.contains("春节"));
        assertTrue(answer.contains("2027年2月6日"), answer);
        long days = ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.of(2027, 2, 6));
        assertTrue(answer.contains("还有 " + days + " 天"), answer);
    }

    @Test
    void yearQuestionReturnsGanZhi() {
        String answer = provider.maybeAnswer("今年是什么年");
        assertNotNull(answer);
        assertTrue(answer.contains("丙午（马）年"), answer);
    }

    @Test
    void festivalQuestionReturnsCountdown() {
        String answer = provider.maybeAnswer("端午节什么时候");
        assertNotNull(answer);
        assertTrue(answer.contains("端午节"), answer);
        assertTrue(answer.contains("还有"), answer);
    }

    @Test
    void jieQiQuestionReturnsSolarDate() {
        String answer = provider.maybeAnswer("2027年立春是哪天");
        assertNotNull(answer);
        assertTrue(answer.contains("立春"), answer);
        assertTrue(answer.contains("日。"), answer);
    }

    @Test
    void nonLunarQuestionReturnsNull() {
        assertNull(provider.maybeAnswer("北京到邯郸有车吗"));
        assertNull(provider.maybeAnswer("现在几点了"));
        assertNull(provider.maybeAnswer("明天天气怎么样"));
        assertNull(provider.maybeAnswer("农历年前后有优惠吗"));
    }
}
