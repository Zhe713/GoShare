package com.goshare.assistant.llm;

import com.nlf.calendar.Lunar;
import com.nlf.calendar.Solar;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 农历计算服务（离线确定性历法，零外部依赖、零延迟、零模型调用）：
 * 公历日期 → 农历年月日 + 干支纪年 + 生肖。
 * 历法计算由 lunar 库确定性完成，杜绝模型凭记忆编造农历日期（历史教训：2026-11-03 被模型答成「十月初五」，实为九月廿五）。
 */
@Component
public class LunarService {

    /** 干支纪年以正月初一为界（lunar 默认口径，与官方对照表一致） */
    private static final boolean GAN_ZHI_FROM_SPRING_FESTIVAL = true;

    /**
     * 返回公历日期对应的农历描述，如「农历丙午（马）年九月廿五」。
     * 计算失败返回 null，由调用方诚实兜底，不编造。
     */
    public String lunarText(LocalDate date) {
        if (date == null) {
            date = LocalDate.now();
        }
        try {
            Solar solar = Solar.fromYmd(date.getYear(), date.getMonthValue(), date.getDayOfMonth());
            Lunar lunar = solar.getLunar();
            return "农历" + lunar.getYearInGanZhi() + "（" + lunar.getYearShengXiao() + "）年"
                    + lunar.getMonthInChinese() + "月" + lunar.getDayInChinese();
        } catch (Exception e) {
            return null;
        }
    }
}
