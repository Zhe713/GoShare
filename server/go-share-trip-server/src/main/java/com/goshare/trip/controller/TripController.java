package com.goshare.trip.controller;

import com.goshare.common.result.Result;
import com.goshare.common.security.CurrentUserResolver;
import com.goshare.trip.dto.TripPublishRequest;
import com.goshare.trip.dto.TripVO;
import com.goshare.trip.service.TripService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 行程接口：发布、搜索、详情、我的行程
 */
@RestController
@RequestMapping("/api/trip")
@RequiredArgsConstructor
public class TripController {

    private final TripService tripService;
    private final CurrentUserResolver currentUserResolver;

    /** 发布行程（仅车主） */
    @PostMapping("/publish")
    public Result<TripVO> publish(@RequestHeader(value = "Authorization", required = false) String auth,
                                  @RequestBody @Valid TripPublishRequest request) {
        Long driverId = currentUserResolver.resolveUserId(auth);
        if (!"DRIVER".equals(currentUserResolver.resolveRole(auth))) {
            return Result.error(403, "仅车主可以发布行程");
        }
        return Result.success(tripService.publish(driverId, request));
    }

    /** 搜索行程 */
    @GetMapping("/search")
    public Result<List<TripVO>> search(@RequestParam("fromCity") String fromCity,
                                       @RequestParam("toCity") String toCity,
                                       @RequestParam(value = "departDate", required = false)
                                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate departDate) {
        return Result.success(tripService.search(fromCity, toCity, departDate));
    }

    /** 行程详情 */
    @GetMapping("/{tripId}")
    public Result<TripVO> detail(@PathVariable("tripId") Long tripId) {
        return Result.success(tripService.detail(tripId));
    }

    /** 我发布的行程 */
    @GetMapping("/mine")
    public Result<List<TripVO>> mine(@RequestHeader(value = "Authorization", required = false) String auth) {
        Long driverId = currentUserResolver.resolveUserId(auth);
        return Result.success(tripService.listByDriver(driverId));
    }
}
