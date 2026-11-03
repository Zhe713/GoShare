package com.goshare.notify.service;

import com.goshare.notify.dto.WeatherVO;

import java.time.LocalDate;

/**
 * 天气数据源抽象（可插拔：mock / http）
 */
public interface WeatherService {

    /** 查询城市实时天气 */
    WeatherVO query(String city);

    /** 查询指定日期天气（date 为空时等价于今天；默认实现保持旧行为） */
    default WeatherVO query(String city, LocalDate date) {
        return query(city);
    }
}
