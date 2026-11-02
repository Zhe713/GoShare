package com.goshare.trip.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.goshare.common.exception.BusinessException;
import com.goshare.trip.dto.TripPublishRequest;
import com.goshare.trip.dto.TripVO;
import com.goshare.trip.entity.Trip;
import com.goshare.trip.es.TripDocument;
import com.goshare.trip.es.TripEsService;
import com.goshare.trip.mapper.TripMapper;
import com.goshare.trip.service.TripService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 行程服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TripServiceImpl implements TripService {

    /** Redis 座位库存键前缀：trip:seats:{行程ID} */
    private static final String SEAT_KEY_PREFIX = "trip:seats:";

    private final TripMapper tripMapper;
    private final StringRedisTemplate redisTemplate;
    private final TripEsService tripEsService;

    @Override
    public TripVO publish(Long driverId, TripPublishRequest request) {
        // 1. 出发时间必须在未来
        if (!request.getDepartureTime().isAfter(LocalDateTime.now())) {
            throw new BusinessException(400, "出发时间必须晚于当前时间");
        }
        // 2. 落库：剩余座位初始等于总座位
        Trip trip = new Trip();
        trip.setDriverId(driverId);
        trip.setFromCity(request.getFromCity());
        trip.setToCity(request.getToCity());
        trip.setDepartureTime(request.getDepartureTime());
        trip.setTotalSeats(request.getTotalSeats());
        trip.setRemainSeats(request.getTotalSeats());
        trip.setPrice(request.getPrice());
        trip.setStatus(0);
        trip.setRemark(request.getRemark());
        tripMapper.insert(trip);
        // 3. 初始化 Redis 座位库存（下单时用 Lua 原子扣减）
        redisTemplate.opsForValue().set(SEAT_KEY_PREFIX + trip.getId(), String.valueOf(request.getTotalSeats()));
        // 4. 同步到 ES 搜索引擎（失败不影响发布）
        tripEsService.index(trip);
        return toVO(trip);
    }

    @Override
    public List<TripVO> search(String fromCity, String toCity, LocalDate departDate) {
        try {
            // 1. 优先走 ES 检索
            List<TripDocument> docs = tripEsService.search(fromCity, toCity, departDate);
            if (docs.isEmpty()) {
                return List.of();
            }
            // 2. 用数据库校准余座等实时字段（ES 侧重检索，DB 保实时）
            List<Long> ids = docs.stream().map(TripDocument::getId).collect(Collectors.toList());
            Map<Long, Trip> dbMap = tripMapper.selectBatchIds(ids).stream()
                    .collect(Collectors.toMap(Trip::getId, Function.identity()));
            return docs.stream()
                    .map(doc -> {
                        Trip trip = dbMap.get(doc.getId());
                        return trip != null ? toVO(trip) : toVOFromDoc(doc);
                    })
                    .collect(Collectors.toList());
        } catch (Exception e) {
            // 2. ES 不可用时降级 MySQL 搜索（演示优雅降级）
            log.warn("ES 搜索不可用，降级 MySQL: {}", e.getMessage());
            return searchFromDb(fromCity, toCity, departDate);
        }
    }

    /** 全量同步行程数据到 ES（运维接口：数据初始化/修复时用） */
    public int syncAllToEs() {
        List<Trip> all = tripMapper.selectList(null);
        all.forEach(tripEsService::index);
        return all.size();
    }

    private List<TripVO> searchFromDb(String fromCity, String toCity, LocalDate departDate) {
        LambdaQueryWrapper<Trip> wrapper = new LambdaQueryWrapper<Trip>()
                .eq(Trip::getFromCity, fromCity)
                .eq(Trip::getToCity, toCity)
                .in(Trip::getStatus, 0, 1)
                .ge(Trip::getDepartureTime, LocalDateTime.now())
                .orderByAsc(Trip::getDepartureTime);
        if (departDate != null) {
            wrapper.ge(Trip::getDepartureTime, departDate.atStartOfDay())
                    .lt(Trip::getDepartureTime, departDate.plusDays(1).atStartOfDay());
        }
        return tripMapper.selectList(wrapper).stream()
                .map(this::toVO).collect(Collectors.toList());
    }

    @Override
    public TripVO detail(Long tripId) {
        Trip trip = getById(tripId);
        return toVO(trip);
    }

    @Override
    public List<TripVO> listByDriver(Long driverId) {
        return tripMapper.selectList(
                        new LambdaQueryWrapper<Trip>()
                                .eq(Trip::getDriverId, driverId)
                                .orderByDesc(Trip::getDepartureTime))
                .stream().map(this::toVO).collect(Collectors.toList());
    }

    @Override
    public Trip getById(Long tripId) {
        Trip trip = tripMapper.selectById(tripId);
        if (trip == null) {
            throw new BusinessException(404, "行程不存在");
        }
        return trip;
    }

    private TripVO toVO(Trip trip) {
        TripVO vo = new TripVO();
        BeanUtils.copyProperties(trip, vo);
        return vo;
    }

    /** ES 文档转 VO（数据库查不到时兜底，保证搜索有结果） */
    private TripVO toVOFromDoc(TripDocument doc) {
        TripVO vo = new TripVO();
        BeanUtils.copyProperties(doc, vo);
        return vo;
    }
}
