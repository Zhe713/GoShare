package com.goshare.notify.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 天气数据源配置（可插拔：mock 默认 / http 接真实 API）
 */
@Data
@Component
@ConfigurationProperties(prefix = "goshare.weather")
public class WeatherProperties {

    /** 数据源模式：mock | http */
    private String mode = "mock";

    /** 真实天气 API 地址（高德/和风等，mode=http 时生效） */
    private String httpUrl;

    /** API Key */
    private String apiKey;
}
