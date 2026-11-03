package com.goshare.assistant.llm;

import com.nlf.calendar.Lunar;
import com.nlf.calendar.Solar;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 农历/历法计算服务（离线确定性历法，零外部依赖、零延迟、零模型调用）：
 * 公历日期 → 农历年月日 + 干支纪年 + 生肖；春节/传统节日倒计时；干支年；二十四节气。
 * 历法计算由 lunar 库确定性完成，杜绝模型凭记忆编造（历史教训：2026-11-03 被模型答成「十月初五」、
 * 2027 春节被模型答成「2月1日」，实为 2027-02-06）。
 */
@Component
public class LunarService {

    /** 二十四节气名（lunar 库命名） */
    private static final Pattern JIE_QI_NAMES = Pattern.compile(
            "立春|雨水|惊蛰|春分|清明|谷雨|立夏|小满|芒种|夏至|小暑|大暑|立秋|处暑|白露|秋分|寒露|霜降|立冬|小雪|大雪|冬至|小寒|大寒");

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

    /**
     * 春节倒计时：从 from（含）起找下一个正月初一，返回如
     * 「距离农历丁未（羊）年春节（2027年2月6日）还有 95 天。」
     */
    public String springFestivalText(LocalDate from) {
        return festivalCountdown(from, "春节", 1, 1);
    }

    /**
     * 农历节日倒计时：如「距离农历丁未（羊）年端午节（2027年6月9日）还有 N 天。」
     * 端午=五月初五、中秋=八月十五、重阳=九月初九、元宵=正月十五、七夕=七月初七。
     */
    public String festivalText(LocalDate from, String name, int lunarMonth, int lunarDay) {
        return festivalCountdown(from, name, lunarMonth, lunarDay);
    }

    /**
     * 干支纪年描述：如「今年是丙午（马）年，今天是农历九月廿五。」
     */
    public String yearText(LocalDate date) {
        if (date == null) {
            date = LocalDate.now();
        }
        String text = lunarText(date);
        if (text == null) {
            return null;
        }
        String year = text.substring(text.indexOf("农历") + 2, text.indexOf("）") + 1);
        String day = text.substring(text.indexOf("年") + 1);
        return "今年是" + year + "年，今天是农历" + day + "。";
    }

    /**
     * 二十四节气日期查询：问句含节气名时返回如「2027年立春是2027年2月4日。」；
     * 优先按问句中出现的公历年份（或「明年」）回答；未识别具体节气返回 null。
     */
    public String jieQiText(LocalDate date, String question) {
        if (date == null) {
            date = LocalDate.now();
        }
        Matcher m = JIE_QI_NAMES.matcher(question);
        if (!m.find()) {
            return null;
        }
        String name = m.group();
        int askedYear = date.getYear();
        Matcher ym = Pattern.compile("(\\d{4})\\s*年").matcher(question);
        if (ym.find()) {
            askedYear = Integer.parseInt(ym.group(1));
        } else if (question.contains("明年")) {
            askedYear += 1;
        }
        Solar fallback = null;
        for (int ly = askedYear - 1; ly <= askedYear + 1; ly++) {
            try {
                Lunar lunar = Lunar.fromYmd(ly, 1, 1);
                Solar hit = findJieQi(lunar.getJieQiTable(), name);
                if (hit == null) {
                    continue;
                }
                if (hit.getYear() == askedYear) {
                    return askedYear + "年" + name + "是" + hit.getYear() + "年"
                            + hit.getMonth() + "月" + hit.getDay() + "日。";
                }
                if (fallback == null) {
                    fallback = hit;
                }
            } catch (Exception ignored) {
                // 个别农历年节气表异常则跳过
            }
        }
        if (fallback != null) {
            return askedYear + "年" + name + "是" + fallback.getYear() + "年"
                    + fallback.getMonth() + "月" + fallback.getDay() + "日。";
        }
        return null;
    }

    /** 节气表内按键名精确匹配，失败再做双向模糊匹配（防键名差异） */
    private Solar findJieQi(Map<String, Solar> table, String name) {
        Solar hit = table.get(name);
        if (hit != null) {
            return hit;
        }
        for (Map.Entry<String, Solar> e : table.entrySet()) {
            if (e.getKey().contains(name) || name.contains(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }

    /** 找 from（含）之后第一个农历 month 月 day 日，返回对应公历 Solar；400 天内未找到返回 null */
    private Solar nextLunarDay(LocalDate from, int lunarMonth, int lunarDay) {
        LocalDate cursor = from;
        for (int i = 0; i < 400; i++) {
            Solar solar = Solar.fromYmd(cursor.getYear(), cursor.getMonthValue(), cursor.getDayOfMonth());
            Lunar lunar = solar.getLunar();
            if (lunar.getMonth() == lunarMonth && lunar.getDay() == lunarDay) {
                return solar;
            }
            cursor = cursor.plusDays(1);
        }
        return null;
    }

    private String festivalCountdown(LocalDate from, String name, int lunarMonth, int lunarDay) {
        LocalDate start = from == null ? LocalDate.now() : from;
        Solar target = nextLunarDay(start, lunarMonth, lunarDay);
        if (target == null) {
            return null;
        }
        LocalDate targetDate = LocalDate.of(target.getYear(), target.getMonth(), target.getDay());
        long days = ChronoUnit.DAYS.between(start, targetDate);
        String text = lunarText(targetDate);
        if (text == null) {
            return null;
        }
        String year = text.substring(text.indexOf("农历") + 2, text.indexOf("）") + 1);
        return "距离农历" + year + "年" + name + "（" + targetDate.getYear() + "年"
                + targetDate.getMonthValue() + "月" + targetDate.getDayOfMonth() + "日）还有 " + days + " 天。";
    }
}
