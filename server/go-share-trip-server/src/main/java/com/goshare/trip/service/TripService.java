package com.goshare.trip.service;

import com.goshare.trip.dto.TripPublishRequest;
import com.goshare.trip.dto.TripVO;
import com.goshare.trip.entity.Trip;

import java.time.LocalDate;
import java.util.List;

/**
 * 行程服务
 */
public interface TripService {

    /** 发布行程（同时初始化 Redis 座位库存） */
    TripVO publish(Long driverId, TripPublishRequest request);

    /** 按路线搜索（出发/到达城市 + 可选日期），只返回有效行程 */
    List<TripVO> search(String fromCity, String toCity, LocalDate departDate);

    /** 行程详情 */
    TripVO detail(Long tripId);

    /** 我发布的行程 */
    List<TripVO> listByDriver(Long driverId);

    /** 按 ID 查询实体（供内部使用） */
    Trip getById(Long tripId);
}
