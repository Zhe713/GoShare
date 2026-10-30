package com.goshare.notify.dto;

import lombok.Data;

/**
 * 天气信息（体验功能：发车天气提醒）
 */
@Data
public class WeatherVO {

    /** 城市 */
    private String city;

    /** 天气现象（晴/多云/小雨等） */
    private String weather;

    /** 温度（℃） */
    private String temperature;

    /** 出行建议 */
    private String advice;
}
