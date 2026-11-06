package com.goshare.trip.service;

import com.goshare.trip.dto.TripPublishRequest;
import com.goshare.trip.dto.TripStopDTO;
import com.goshare.trip.dto.TripVO;
import com.goshare.trip.entity.Trip;

import java.time.LocalDate;
import java.util.List;

/**
 * 行程服务
 */
public interface TripService {

    /** 发布行程（落站点序列 + 区间库存 + 车辆快照 + 里程计价） */
    TripVO publish(Long driverId, TripPublishRequest request);

    /** 按路线搜索（出发/到达城市 + 可选日期），只返回有效行程 */
    List<TripVO> search(String fromCity, String toCity, LocalDate departDate);

    /** 行程详情 */
    TripVO detail(Long tripId);

    /** 我发布的行程 */
    List<TripVO> listByDriver(Long driverId);

    /** 按 ID 查询实体（供内部使用） */
    Trip getById(Long tripId);

    /** 全量同步行程数据到 ES 搜索引擎（运维/初始化用） */
    int syncAllToEs();

    /** 车主取消行程（阶段1新增） */
    TripVO cancelTrip(Long driverId, Long tripId);

    /** 车主标记发车（阶段1新增） */
    TripVO depart(Long driverId, Long tripId);

    /** 车主标记到达完成（阶段1新增） */
    TripVO complete(Long driverId, Long tripId);

    /** 查询行程站点序列（供订单服务计算区间与里程，阶段1新增） */
    List<TripStopDTO> listStops(Long tripId);
}
