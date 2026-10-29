package com.goshare.marketing.controller;

import com.goshare.common.result.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查：确认服务是否存活
 */
@RestController
@RequestMapping("/api/ping")
public class PingController {

    @GetMapping
    public Result<String> ping() {
        return Result.success("go-share-marketing-server");
    }
}
