package com.goshare.assistant.llm;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

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
}
