package com.goshare.notify;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 通知服务启动类
 */
@SpringBootApplication(scanBasePackages = "com.goshare")
@MapperScan("com.goshare.notify.mapper")
public class NotifyServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotifyServerApplication.class, args);
    }
}
