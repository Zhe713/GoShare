package com.goshare.trip.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.goshare.common.exception.BusinessException;
import com.goshare.trip.dto.TripPublishRequest;
import com.goshare.trip.dto.TripStopDTO;
import com.goshare.trip.dto.TripVO;
import com.goshare.trip.entity.Trip;
import com.goshare.trip.entity.TripSegment;
import com.goshare.trip.entity.TripStop;
import com.goshare.trip.es.TripDocument;
import com.goshare.trip.es.TripEsService;
import com.goshare.trip.mapper.TripMapper;
import com.goshare.trip.mapper.TripSegmentMapper;
import com.goshare.trip.mapper.TripStopMapper;
import com.goshare.trip.service.TripService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 行程服务实现
 *
 * <p>阶段1「换骨」：从"全程一个标量余座"升级为"站点序列 + 区间覆盖计数"。
 * 发布时落站点与区间，计价改为「区间里程 × 每公里单价」，并快照车辆与车主昵称。
 * 行程状态机：0待出发 → 1满座 → 2进行中 → 3已完成 / 4已取消。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TripServiceImpl implements TripService {

    /** Redis 座位库存键前缀：trip:seats:{行程ID}（兼容旧版全程计数，区间计数以 trip_segment 为准） */
    private static final String SEAT_KEY_PREFIX = "trip:seats:";

    private final TripMapper tripMapper;
    private final TripStopMapper tripStopMapper;
    private final TripSegmentMapper tripSegmentMapper;
    private final StringRedisTemplate redisTemplate;
    private final TripEsService tripEsService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TripVO publish(Long driverId, TripPublishRequest request) {
        // 1. 出发时间必须在未来
        if (!request.getDepartureTime().isAfter(LocalDateTime.now())) {
            throw new BusinessException(400, "出发时间必须晚于当前时间");
        }
        // 2. 构建站点序列：未传站点时退化为起终点两站（兼容旧调用）
        List<TripStopDTO> stops = normalizeStops(request);
        // 3. 按站点坐标估算里程（缺坐标时用城市距离表），并落区间
        BigDecimal totalDistance = calcTotalDistance(stops);
        // 4. 落库行程主表
        Trip trip = new Trip();
        trip.setDriverId(driverId);
        trip.setFromCity(request.getFromCity());
        trip.setToCity(request.getToCity());
        trip.setDepartureTime(request.getDepartureTime());
        trip.setTotalSeats(request.getTotalSeats());
        trip.setRemainSeats(request.getTotalSeats());
        trip.setPrice(request.getPrice());
        trip.setPricePerKm(request.getPricePerKm());
        trip.setTotalDistanceKm(totalDistance);
        trip.setStatus(Trip.STATUS_PUBLISHED);
        trip.setRemark(request.getRemark());
        trip.setVehiclePlate(request.getVehiclePlate());
        trip.setVehicleModel(request.getVehicleModel());
        trip.setVehicleColor(request.getVehicleColor());
        tripMapper.insert(trip);

        // 5. 落站点序列
        for (int i = 0; i < stops.size(); i++) {
            TripStopDTO s = stops.get(i);
            TripStop stop = new TripStop();
            stop.setTripId(trip.getId());
            stop.setSeq(i);
            stop.setCity(s.getCity());
            stop.setAddress(s.getAddress());
            stop.setLng(s.getLng());
            stop.setLat(s.getLat());
            stop.setOffsetMinutes(s.getOffsetMinutes() != null ? s.getOffsetMinutes() : 0);
            tripStopMapper.insert(stop);
        }

        // 6. 落区间库存：相邻站点间各一段，初始余座 = 总座位
        for (int i = 0; i < stops.size() - 1; i++) {
            TripSegment seg = new TripSegment();
            seg.setTripId(trip.getId());
            seg.setFromSeq(i);
            seg.setToSeq(i + 1);
            seg.setDistanceKm(segmentDistance(stops.get(i), stops.get(i + 1)));
            seg.setRemainSeats(request.getTotalSeats());
            seg.setTotalSeats(request.getTotalSeats());
            tripSegmentMapper.insert(seg);
        }

        // 7. 初始化 Redis 库存：per-segment 区间 key（order 下单 Lua 多 key 原子扣减）+ 全程标量 key（兼容旧链路）
        for (int i = 0; i < stops.size() - 1; i++) {
            redisTemplate.opsForValue().set(
                    "trip:seg:" + trip.getId() + ":" + i + "-" + (i + 1),
                    String.valueOf(request.getTotalSeats()));
        }
        redisTemplate.opsForValue().set(SEAT_KEY_PREFIX + trip.getId(), String.valueOf(request.getTotalSeats()));
        // 8. 同步到 ES（失败不影响发布）
        tripEsService.index(trip);
        return toVOWithDetail(trip);
    }

    /**
     * 站点序列归一化：未传 stops 时退化为 [起点, 终点] 两站，保证区间库存仍成立。
     */
    private List<TripStopDTO> normalizeStops(TripPublishRequest request) {
        if (request.getStops() != null && !request.getStops().isEmpty()) {
            return request.getStops();
        }
        TripStopDTO from = new TripStopDTO();
        from.setCity(request.getFromCity());
        from.setOffsetMinutes(0);
        TripStopDTO to = new TripStopDTO();
        to.setCity(request.getToCity());
        return List.of(from, to);
    }

    /**
     * 区间里程估算：优先用两站经纬度算球面距离；缺坐标时用城市距离表（演示用近似）。
     */
    private BigDecimal segmentDistance(TripStopDTO a, TripStopDTO b) {
        if (a.getLng() != null && a.getLat() != null && b.getLng() != null && b.getLat() != null) {
            return haversine(a.getLat(), a.getLng(), b.getLat(), b.getLng());
        }
        // 城市距离表（演示：常见城际近似里程，公里）
        return BigDecimal.valueOf(cityDistance(a.getCity(), b.getCity()));
    }

    private BigDecimal calcTotalDistance(List<TripStopDTO> stops) {
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < stops.size() - 1; i++) {
            total = total.add(segmentDistance(stops.get(i), stops.get(i + 1)));
        }
        return total;
    }

    /** 球面距离（Haversine 公式），返回公里 */
    private BigDecimal haversine(BigDecimal lat1, BigDecimal lng1, BigDecimal lat2, BigDecimal lng2) {
        double r = 6371.0;
        double dLat = Math.toRadians(lat2.doubleValue() - lat1.doubleValue());
        double dLng = Math.toRadians(lng2.doubleValue() - lng1.doubleValue());
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1.doubleValue())) * Math.cos(Math.toRadians(lat2.doubleValue()))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return BigDecimal.valueOf(r * c).setScale(2, RoundingMode.HALF_UP);
    }

    /** 城市间近似里程表（公里，演示用；无命中时按 100km 兜底） */
    private double cityDistance(String cityA, String cityB) {
        // 简化：同名城市距离 0，否则用预置城际距离，兜底 100
        if (cityA == null || cityB == null || cityA.equals(cityB)) {
            return 0;
        }
        String key = cityA + "-" + cityB;
        String rev = cityB + "-" + cityA;
        return CITY_DISTANCE.getOrDefault(key, CITY_DISTANCE.getOrDefault(rev, 100.0));
    }

    /** 预置城际近似里程（公里），覆盖演示常用线路 */
    private static final Map<String, Double> CITY_DISTANCE = Map.ofEntries(
            Map.entry("北京-上海", 1213.0),
            Map.entry("北京-天津", 137.0),
            Map.entry("北京-济南", 437.0),
            Map.entry("济南-上海", 835.0),
            Map.entry("石家庄-邯郸", 260.0),
            Map.entry("石家庄-北京", 290.0),
            Map.entry("广州-深圳", 139.0),
            Map.entry("上海-杭州", 177.0),
            Map.entry("上海-南京", 300.0),
            Map.entry("成都-重庆", 300.0)
    );

    @Override
    public List<TripVO> search(String fromCity, String toCity, LocalDate departDate) {
        try {
            List<TripDocument> docs = tripEsService.search(fromCity, toCity, departDate);
            if (docs.isEmpty()) {
                return List.of();
            }
            List<Long> ids = docs.stream().map(TripDocument::getId).collect(Collectors.toList());
            Map<Long, Trip> dbMap = tripMapper.selectBatchIds(ids).stream()
                    .collect(Collectors.toMap(Trip::getId, Function.identity()));
            return docs.stream()
                    .map(doc -> {
                        Trip trip = dbMap.get(doc.getId());
                        return trip != null ? toVOWithDetail(trip) : toVOFromDoc(doc);
                    })
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("ES 搜索不可用，降级 MySQL: {}", e.getMessage());
            return searchFromDb(fromCity, toCity, departDate);
        }
    }

    /** 全量同步行程数据到 ES（运维接口：数据初始化/修复时用） */
    @Override
    public int syncAllToEs() {
        List<Trip> all = tripMapper.selectList(null);
        all.forEach(tripEsService::index);
        return all.size();
    }

    private List<TripVO> searchFromDb(String fromCity, String toCity, LocalDate departDate) {
        LambdaQueryWrapper<Trip> wrapper = new LambdaQueryWrapper<Trip>()
                .eq(Trip::getFromCity, fromCity)
                .eq(Trip::getToCity, toCity)
                .in(Trip::getStatus, Trip.STATUS_PUBLISHED, Trip.STATUS_FULL)
                .ge(Trip::getDepartureTime, LocalDateTime.now())
                .orderByAsc(Trip::getDepartureTime);
        if (departDate != null) {
            wrapper.ge(Trip::getDepartureTime, departDate.atStartOfDay())
                    .lt(Trip::getDepartureTime, departDate.plusDays(1).atStartOfDay());
        }
        return tripMapper.selectList(wrapper).stream()
                .map(this::toVOWithDetail).collect(Collectors.toList());
    }

    @Override
    public TripVO detail(Long tripId) {
        Trip trip = getById(tripId);
        ensureStock(trip);
        return toVOWithDetail(trip);
    }

    /** 库存自愈：全程/区间 Redis key 缺失时按 DB 余座初始化（兼容种子/直插行程，保证下单可扣） */
    private void ensureStock(Trip trip) {
        try {
            String key = SEAT_KEY_PREFIX + trip.getId();
            if (!Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
                redisTemplate.opsForValue().set(key, String.valueOf(trip.getRemainSeats()));
            }
            // 区间 key 自愈：按 DB trip_segment 的当前余座逐段初始化
            List<TripSegment> segs = tripSegmentMapper.selectList(
                    new LambdaQueryWrapper<TripSegment>().eq(TripSegment::getTripId, trip.getId()));
            for (TripSegment seg : segs) {
                String segKey = "trip:seg:" + trip.getId() + ":" + seg.getFromSeq() + "-" + seg.getToSeq();
                if (!Boolean.TRUE.equals(redisTemplate.hasKey(segKey))) {
                    redisTemplate.opsForValue().set(segKey, String.valueOf(seg.getRemainSeats()));
                }
            }
        } catch (Exception e) {
            log.warn("初始化行程库存失败 tripId={}", trip.getId(), e);
        }
    }

    @Override
    public List<TripVO> listByDriver(Long driverId) {
        return tripMapper.selectList(
                        new LambdaQueryWrapper<Trip>()
                                .eq(Trip::getDriverId, driverId)
                                .orderByDesc(Trip::getDepartureTime))
                .stream().map(this::toVOWithDetail).collect(Collectors.toList());
    }

    @Override
    public Trip getById(Long tripId) {
        Trip trip = tripMapper.selectById(tripId);
        if (trip == null) {
            throw new BusinessException(404, "行程不存在");
        }
        return trip;
    }

    // ---------- 行程状态机（阶段1新增） ----------

    /** 车主取消行程：置已取消，通知订单服务批量退单（退座退券），并下架 ES */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public TripVO cancelTrip(Long driverId, Long tripId) {
        Trip trip = getById(tripId);
        if (!trip.getDriverId().equals(driverId)) {
            throw new BusinessException(403, "仅车主可取消行程");
        }
        if (trip.getStatus() != Trip.STATUS_PUBLISHED && trip.getStatus() != Trip.STATUS_FULL) {
            throw new BusinessException(400, "当前行程状态不可取消（已发车或已结束）");
        }
        int rows = tripMapper.update(null, new LambdaUpdateWrapper<Trip>()
                .eq(Trip::getId, tripId)
                .eq(Trip::getStatus, trip.getStatus())
                .set(Trip::getStatus, Trip.STATUS_CANCELLED));
        if (rows == 0) {
            throw new BusinessException(409, "行程状态已变化，请刷新后重试");
        }
        tripEsService.remove(tripId);
        trip.setStatus(Trip.STATUS_CANCELLED);
        return toVOWithDetail(trip);
    }

    /** 车主标记发车：0待出发 → 2进行中 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public TripVO depart(Long driverId, Long tripId) {
        Trip trip = getById(tripId);
        if (!trip.getDriverId().equals(driverId)) {
            throw new BusinessException(403, "仅车主可操作");
        }
        if (trip.getStatus() != Trip.STATUS_PUBLISHED && trip.getStatus() != Trip.STATUS_FULL) {
            throw new BusinessException(400, "仅待出发或满座行程可发车");
        }
        int rows = tripMapper.update(null, new LambdaUpdateWrapper<Trip>()
                .eq(Trip::getId, tripId)
                .eq(Trip::getStatus, trip.getStatus())
                .set(Trip::getStatus, Trip.STATUS_DEPARTED));
        if (rows == 0) {
            throw new BusinessException(409, "行程状态已变化，请刷新后重试");
        }
        trip.setStatus(Trip.STATUS_DEPARTED);
        return toVOWithDetail(trip);
    }

    /** 车主标记到达：2进行中 → 3已完成 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public TripVO complete(Long driverId, Long tripId) {
        Trip trip = getById(tripId);
        if (!trip.getDriverId().equals(driverId)) {
            throw new BusinessException(403, "仅车主可操作");
        }
        if (trip.getStatus() != Trip.STATUS_DEPARTED) {
            throw new BusinessException(400, "仅进行中行程可标记完成");
        }
        int rows = tripMapper.update(null, new LambdaUpdateWrapper<Trip>()
                .eq(Trip::getId, tripId)
                .eq(Trip::getStatus, Trip.STATUS_DEPARTED)
                .set(Trip::getStatus, Trip.STATUS_COMPLETED));
        if (rows == 0) {
            throw new BusinessException(409, "行程状态已变化，请刷新后重试");
        }
        trip.setStatus(Trip.STATUS_COMPLETED);
        return toVOWithDetail(trip);
    }

    /** 查询行程站点序列（供订单服务计算区间与里程） */
    @Override
    public List<TripStopDTO> listStops(Long tripId) {
        return tripStopMapper.selectList(new LambdaQueryWrapper<TripStop>()
                        .eq(TripStop::getTripId, tripId)
                        .orderByAsc(TripStop::getSeq))
                .stream().map(s -> {
                    TripStopDTO dto = new TripStopDTO();
                    dto.setCity(s.getCity());
                    dto.setAddress(s.getAddress());
                    dto.setLng(s.getLng());
                    dto.setLat(s.getLat());
                    dto.setOffsetMinutes(s.getOffsetMinutes());
                    return dto;
                }).collect(Collectors.toList());
    }

    // ---------- 私有方法 ----------

    private TripVO toVO(Trip trip) {
        TripVO vo = new TripVO();
        BeanUtils.copyProperties(trip, vo);
        return vo;
    }

    /** 带站点序列的 VO（详情/搜索/列表统一展示） */
    private TripVO toVOWithDetail(Trip trip) {
        TripVO vo = toVO(trip);
        try {
            vo.setStops(listStops(trip.getId()));
        } catch (Exception e) {
            log.warn("行程站点序列获取失败 tripId={}: {}", trip.getId(), e.getMessage());
            vo.setStops(new ArrayList<>());
        }
        return vo;
    }

    /** ES 文档转 VO（数据库查不到时兜底，保证搜索有结果） */
    private TripVO toVOFromDoc(TripDocument doc) {
        TripVO vo = new TripVO();
        BeanUtils.copyProperties(doc, vo);
        vo.setStops(new ArrayList<>());
        return vo;
    }
}
