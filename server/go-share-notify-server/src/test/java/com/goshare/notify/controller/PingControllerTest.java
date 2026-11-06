package com.goshare.notify.controller;

import com.goshare.common.result.Result;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 健康检查：返回服务名，供 k8s/网关探活
 */
class PingControllerTest {

    @Test
    void pingReturnsServiceName() {
        Result<String> r = new PingController().ping();

        assertEquals(200, r.getCode());
        assertEquals("go-share-notify-server", r.getData());
    }
}
