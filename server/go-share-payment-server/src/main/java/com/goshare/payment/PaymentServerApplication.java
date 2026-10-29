package com.goshare.payment;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 支付服务启动类
 */
@SpringBootApplication(scanBasePackages = "com.goshare")
@MapperScan("com.goshare.payment.mapper")
public class PaymentServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentServerApplication.class, args);
    }
}
