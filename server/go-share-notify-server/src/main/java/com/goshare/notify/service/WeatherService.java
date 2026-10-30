package com.goshare.notify.service;

import com.goshare.notify.dto.WeatherVO;

/**
 * 天气数据源抽象（可插拔：mock / http）
 */
public interface WeatherService {

    /** 查询城市实时天气 */
    WeatherVO query(String city);
}
