package com.goshare.assistant.nl;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A1 NL→结构化解析器 golden 用例
 */
class NlTripParserTest {

    @Test
    void 完整条件解析_城市时间大件() {
        // 以当前周为基准解析"周五"
        NlTripQuery q = NlTripParser.parse("周五18点从石家庄去邯郸，能带大件");
        assertEquals("石家庄", q.getFromCity());
        assertEquals("邯郸", q.getToCity());
        assertEquals(18, q.getHourPrefer().intValue());
        assertTrue(Boolean.TRUE.equals(q.getLargeLuggage()));
        // 周五落在本自然周内（未来7天内）
        assertTrue(q.getDepartDate().isAfter(LocalDate.now().minusDays(1)));
        assertTrue(q.getDepartDate().isBefore(LocalDate.now().plusDays(8)));
    }

    @Test
    void 明天去北京_只带到达() {
        NlTripQuery q = NlTripParser.parse("明天去北京");
        assertNull(q.getFromCity());
        assertEquals("北京", q.getToCity());
        assertEquals(LocalDate.now().plusDays(1), q.getDepartDate());
        assertNull(q.getHourPrefer());
    }

    @Test
    void 便宜偏好_识别() {
        NlTripQuery q = NlTripParser.parse("便宜点的顺风车");
        assertTrue(Boolean.TRUE.equals(q.getPreferCheap()));
    }

    @Test
    void 无识别条件_返回空查询() {
        NlTripQuery q = NlTripParser.parse("随便看看");
        assertNull(q.getFromCity());
        assertNull(q.getToCity());
        assertNull(q.getDepartDate());
    }

    @Test
    void 今天与明天_边界() {
        NlTripQuery today = NlTripParser.parse("今天从石家庄到北京");
        assertEquals(LocalDate.now(), today.getDepartDate());
        NlTripQuery tomorrow = NlTripParser.parse("后天去天津");
        assertEquals(LocalDate.now().plusDays(2), tomorrow.getDepartDate());
    }
}
