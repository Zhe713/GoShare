package com.goshare.trip;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 行程服务启动类
 */
@SpringBootApplication(scanBasePackages = "com.goshare")
@MapperScan("com.goshare.trip.mapper")
public class TripServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(TripServerApplication.class, args);
    }
}
