package com.goshare.assistant.llm;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 农历计算服务：离线确定性历法，交叉验证官方示例与三源核实日期
 */
class LunarServiceTest {

    private final LunarService service = new LunarService();

    @Test
    void knownDateMatchesTripleSourceVerifiedLunar() {
        // 2026-11-03 真实农历 = 丙午（马）年九月廿五（万年历 / 香港天文台对照表 / 本地算法三源核实；
        // 模型曾幻觉为「十月初五」，此处必须是确定性计算结果）
        assertEquals("农历丙午（马）年九月廿五", service.lunarText(LocalDate.of(2026, 11, 3)));
    }

    @Test
    void springFestivalIsFirstDayOfNewLunarYear() {
        // 2026 年春节 = 2026-02-17，即丙午（马）年正月初一
        assertEquals("农历丙午（马）年正月初一", service.lunarText(LocalDate.of(2026, 2, 17)));
    }

    @Test
    void matchesOfficialReadmeSample() {
        // lunar 官方示例：农历 1986-04-21 对应公历 1986-05-29，丙寅（虎）年
        assertEquals("农历丙寅（虎）年四月廿一", service.lunarText(LocalDate.of(1986, 5, 29)));
    }

    @Test
    void nullDateFallsBackToToday() {
        String text = service.lunarText(null);
        assertNotNull(text);
        assertTrue(text.startsWith("农历"));
    }

    @Test
    void lunarYearBoundaryUsesSpringFestivalCriterion() {
        // 正月初一前一日仍属上一农历年：2026-02-16 为乙巳（蛇）年腊月廿九（除夕前一日）
        assertEquals("农历乙巳（蛇）年腊月廿九", service.lunarText(LocalDate.of(2026, 2, 16)));
    }

    @Test
    void springFestival2027IsFeb6NotFeb1() {
        // 模型曾编造「2027年春节是2月1日」；确定性计算必须是 2027-02-06 丁未（羊）年正月初一
        assertEquals("农历丁未（羊）年正月初一", service.lunarText(LocalDate.of(2027, 2, 6)));
    }

    @Test
    void springFestivalCountdownFromNov3() {
        // 2026-11-03 → 2027-02-06 = 95 天
        String text = service.springFestivalText(LocalDate.of(2026, 11, 3));
        assertNotNull(text);
        assertTrue(text.contains("春节"));
        assertTrue(text.contains("2027年2月6日"));
        assertTrue(text.contains("还有 95 天"), text);
    }

    @Test
    void dragonBoatFestivalCountdown() {
        String text = service.festivalText(LocalDate.of(2026, 11, 3), "端午节", 5, 5);
        assertNotNull(text);
        assertTrue(text.contains("端午节"));
        assertTrue(text.matches(".*还有 \\d+ 天。"), text);
    }

    @Test
    void yearTextIncludesGanZhiAndShengXiao() {
        String text = service.yearText(LocalDate.of(2026, 11, 3));
        assertNotNull(text);
        assertTrue(text.contains("丙午（马）年"), text);
        assertTrue(text.contains("农历九月廿五"), text);
    }

    @Test
    void jieQiTextReturnsSolarDate() {
        String text = service.jieQiText(LocalDate.of(2026, 11, 3), "2027年立春是哪天");
        assertNotNull(text);
        assertTrue(text.contains("2027年立春是2027年2月4日"), text);
    }

    @Test
    void jieQiTextWithoutYearUsesCurrentYear() {
        String text = service.jieQiText(LocalDate.of(2026, 11, 3), "冬至是哪天");
        assertNotNull(text);
        assertTrue(text.contains("2026年冬至是2026年"), text);
    }

    @Test
    void dateCalcWeekdayIsDeterministic() {
        // 模型曾把 2026-12-15 口头算成周一（实为周二：距 11-03 恰好 42 天 = 6 周整）
        assertEquals(6, ChronoUnit.WEEKS.between(LocalDate.of(2026, 11, 3), LocalDate.of(2026, 12, 15)));
    }
}
