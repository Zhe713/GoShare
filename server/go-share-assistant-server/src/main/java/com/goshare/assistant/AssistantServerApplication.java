package com.goshare.assistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 出行助手服务启动类
 */
@SpringBootApplication
@EnableScheduling
public class AssistantServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(AssistantServerApplication.class, args);
    }
}
