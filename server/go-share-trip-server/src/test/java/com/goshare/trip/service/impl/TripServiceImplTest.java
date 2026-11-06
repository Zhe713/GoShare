package com.goshare.trip.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.goshare.common.exception.BusinessException;
import com.goshare.trip.dto.TripPublishRequest;
import com.goshare.trip.dto.TripVO;
import com.goshare.trip.entity.Trip;
import com.goshare.trip.es.TripDocument;
import com.goshare.trip.es.TripEsService;
import com.goshare.trip.mapper.TripMapper;
import com.goshare.trip.mapper.TripSegmentMapper;
import com.goshare.trip.mapper.TripStopMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 行程服务：发布校验/Redis 库存初始化/ES 搜索与降级/详情
 */
class TripServiceImplTest {

    static {
        // LambdaUpdateWrapper 的 set(Trip::getStatus, ...) 依赖实体元数据缓存；
        // 纯单测没有 MyBatis 启动流程，需要手动初始化一次表信息
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Trip.class);
    }

    private TripMapper tripMapper;
    private TripStopMapper tripStopMapper;
    private TripSegmentMapper tripSegmentMapper;
    private StringRedisTemplate redisTemplate;
    private TripEsService tripEsService;
    private TripServiceImpl service;

    @BeforeEach
    void setUp() {
        tripMapper = mock(TripMapper.class);
        tripStopMapper = mock(TripStopMapper.class);
        tripSegmentMapper = mock(TripSegmentMapper.class);
        redisTemplate = mock(StringRedisTemplate.class);
        tripEsService = mock(TripEsService.class);
        service = new TripServiceImpl(tripMapper, tripStopMapper, tripSegmentMapper, redisTemplate, tripEsService);
    }

    private TripPublishRequest futureRequest() {
        TripPublishRequest req = new TripPublishRequest();
        req.setFromCity("石家庄");
        req.setToCity("邯郸");
        req.setDepartureTime(LocalDateTime.now().plusHours(5));
        req.setTotalSeats(3);
        req.setPrice(new BigDecimal("40"));
        req.setRemark("可带大件");
        return req;
    }

    @Test
    void publishRejectsPastDeparture() {
        TripPublishRequest req = futureRequest();
        req.setDepartureTime(LocalDateTime.now().minusMinutes(1));
        BusinessException ex = assertThrows(BusinessException.class, () -> service.publish(1L, req));
        assertEquals(400, ex.getCode());
        verify(tripMapper, never()).insert(any(Trip.class));
    }

    @Test
    void publishSuccessInitializesSeatsAndIndexes() {
        TripPublishRequest req = futureRequest();
        Trip saved = new Trip();
        saved.setId(10L);
        saved.setTotalSeats(3);
        saved.setRemainSeats(3);
        when(tripMapper.insert(any(Trip.class))).thenAnswer(inv -> {
            Trip t = inv.getArgument(0);
            t.setId(10L);
            return 1;
        });
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        TripVO vo = service.publish(1L, req);

        assertEquals(10L, vo.getId());
        assertEquals(3, vo.getRemainSeats());
        verify(valueOps).set("trip:seats:10", "3");
        verify(tripEsService).index(any(Trip.class));
    }

    @Test
    void searchReturnsEmptyWhenEsEmpty() {
        when(tripEsService.search(anyString(), anyString(), any())).thenReturn(List.of());
        List<TripVO> list = service.search("石家庄", "邯郸", LocalDate.now());
        assertTrue(list.isEmpty());
        verify(tripMapper, never()).selectBatchIds(any(Collection.class));
    }

    @Test
    void searchCalibratesRemainSeatsFromDb() {
        TripDocument doc = new TripDocument();
        doc.setId(3L);
        doc.setFromCity("石家庄");
        doc.setToCity("邯郸");
        Trip db = new Trip();
        db.setId(3L);
        db.setFromCity("石家庄");
        db.setToCity("邯郸");
        db.setRemainSeats(1); // DB 实时余座
        when(tripEsService.search(anyString(), anyString(), any())).thenReturn(List.of(doc));
        when(tripMapper.selectBatchIds(any(Collection.class))).thenReturn(List.of(db));

        List<TripVO> list = service.search("石家庄", "邯郸", null);

        assertEquals(1, list.size());
        assertEquals(1, list.get(0).getRemainSeats());
    }

    @Test
    void searchFallsBackToDbWhenEsDown() {
        when(tripEsService.search(anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("es down"));
        Trip dbTrip = new Trip();
        dbTrip.setId(4L);
        dbTrip.setFromCity("石家庄");
        dbTrip.setToCity("北京");
        dbTrip.setDepartureTime(LocalDateTime.now().plusDays(1));
        dbTrip.setRemainSeats(2);
        when(tripMapper.selectList(any(Wrapper.class))).thenReturn(List.of(dbTrip));

        List<TripVO> list = service.search("石家庄", "北京", null);

        assertEquals(1, list.size());
        assertEquals(4L, list.get(0).getId());
    }

    @Test
    void detailNotFound() {
        when(tripMapper.selectById(99L)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.detail(99L));
        assertEquals(404, ex.getCode());
    }

    @Test
    void listByDriverOrdersDesc() {
        Trip t = new Trip();
        t.setId(1L);
        when(tripMapper.selectList(any(Wrapper.class))).thenReturn(List.of(t));
        List<TripVO> list = service.listByDriver(1L);
        assertEquals(1, list.size());
    }

    @Test
    void syncAllToEsIndexesAll() {
        Trip t1 = new Trip();
        t1.setId(1L);
        Trip t2 = new Trip();
        t2.setId(2L);
        when(tripMapper.selectList(null)).thenReturn(List.of(t1, t2));
        int n = service.syncAllToEs();
        assertEquals(2, n);
        verify(tripEsService, times(2)).index(any(Trip.class));
    }

    // ---------- 区间库存（3 站 2 区间） ----------

    private TripPublishRequest threeStopRequest() {
        TripPublishRequest req = futureRequest();
        com.goshare.trip.dto.TripStopDTO a = new com.goshare.trip.dto.TripStopDTO();
        a.setCity("石家庄");
        a.setAddress("石家庄站西广场");
        a.setOffsetMinutes(0);
        com.goshare.trip.dto.TripStopDTO b = new com.goshare.trip.dto.TripStopDTO();
        b.setCity("邢台");
        b.setAddress("邢台东站");
        b.setOffsetMinutes(55);
        com.goshare.trip.dto.TripStopDTO c = new com.goshare.trip.dto.TripStopDTO();
        c.setCity("邯郸");
        c.setAddress("邯郸东站");
        c.setOffsetMinutes(105);
        req.setStops(List.of(a, b, c));
        return req;
    }

    @Test
    void publishThreeStopsCreatesTwoSegmentsAndSegmentKeys() {
        when(tripMapper.insert(any(Trip.class))).thenAnswer(inv -> {
            ((Trip) inv.getArgument(0)).setId(20L);
            return 1;
        });
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(tripSegmentMapper.insert(any(com.goshare.trip.entity.TripSegment.class))).thenReturn(1);
        when(tripStopMapper.insert(any(com.goshare.trip.entity.TripStop.class))).thenReturn(1);
        when(tripStopMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        TripVO vo = service.publish(1L, threeStopRequest());

        assertEquals(20L, vo.getId());
        // 3 站 → 2 个区间，各插一条 trip_segment，并初始化两个区间闸门 key
        verify(tripSegmentMapper, times(2)).insert(any(com.goshare.trip.entity.TripSegment.class));
        verify(tripStopMapper, times(3)).insert(any(com.goshare.trip.entity.TripStop.class));
        verify(valueOps).set("trip:seg:20:0-1", "3");
        verify(valueOps).set("trip:seg:20:1-2", "3");
        verify(valueOps).set("trip:seats:20", "3");
    }

    @Test
    void publishWithoutStopsDegradesToTwoStops() {
        when(tripMapper.insert(any(Trip.class))).thenAnswer(inv -> {
            ((Trip) inv.getArgument(0)).setId(21L);
            return 1;
        });
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(tripStopMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        service.publish(1L, futureRequest());

        // 兼容旧调用：没传站点也要有 2 站 1 区间，区间库存才不会退化成无区间
        verify(tripStopMapper, times(2)).insert(any(com.goshare.trip.entity.TripStop.class));
        verify(tripSegmentMapper, times(1)).insert(any(com.goshare.trip.entity.TripSegment.class));
        verify(valueOps).set("trip:seg:21:0-1", "3");
    }

    @Test
    void detailIncludesStops() {
        Trip t = new Trip();
        t.setId(5L);
        t.setFromCity("石家庄");
        t.setToCity("邯郸");
        when(tripMapper.selectById(5L)).thenReturn(t);
        com.goshare.trip.entity.TripStop stop = new com.goshare.trip.entity.TripStop();
        stop.setTripId(5L);
        stop.setSeq(0);
        stop.setCity("石家庄");
        stop.setAddress("石家庄站西广场");
        stop.setOffsetMinutes(0);
        when(tripStopMapper.selectList(any(Wrapper.class))).thenReturn(List.of(stop));

        TripVO vo = service.detail(5L);

        assertEquals(1, vo.getStops().size());
        assertEquals("石家庄", vo.getStops().get(0).getCity());
    }

    // ---------- 车主生命周期：取消 / 发车 / 到达 ----------

    private Trip publishedTrip(long id, long driverId) {
        Trip t = new Trip();
        t.setId(id);
        t.setDriverId(driverId);
        t.setStatus(Trip.STATUS_PUBLISHED);
        t.setRemainSeats(2);
        return t;
    }

    @Test
    void cancelTripRejectsNonOwner() {
        when(tripMapper.selectById(5L)).thenReturn(publishedTrip(5L, 1L));
        BusinessException ex = assertThrows(BusinessException.class, () -> service.cancelTrip(2L, 5L));
        assertEquals(403, ex.getCode());
    }

    @Test
    void cancelTripSuccessRemovesIndex() {
        when(tripMapper.selectById(5L)).thenReturn(publishedTrip(5L, 1L));
        when(tripMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(tripStopMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        TripVO vo = service.cancelTrip(1L, 5L);

        assertEquals(Trip.STATUS_CANCELLED, vo.getStatus());
        verify(tripEsService).remove(5L);
    }

    @Test
    void cancelTripDetectsConcurrentChange() {
        when(tripMapper.selectById(5L)).thenReturn(publishedTrip(5L, 1L));
        // CAS 影响行数为 0：说明状态已被别人改掉
        when(tripMapper.update(isNull(), any(Wrapper.class))).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.cancelTrip(1L, 5L));

        assertEquals(409, ex.getCode());
    }

    @Test
    void departThenCompleteHappyPath() {
        when(tripMapper.selectById(5L)).thenReturn(publishedTrip(5L, 1L));
        when(tripMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(tripStopMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        assertEquals(Trip.STATUS_DEPARTED, service.depart(1L, 5L).getStatus());

        Trip departed = publishedTrip(5L, 1L);
        departed.setStatus(Trip.STATUS_DEPARTED);
        when(tripMapper.selectById(5L)).thenReturn(departed);
        assertEquals(Trip.STATUS_COMPLETED, service.complete(1L, 5L).getStatus());
    }

    @Test
    void completeRejectsTripNotDeparted() {
        when(tripMapper.selectById(5L)).thenReturn(publishedTrip(5L, 1L));
        BusinessException ex = assertThrows(BusinessException.class, () -> service.complete(1L, 5L));
        assertEquals(400, ex.getCode());
    }

    @Test
    void listStopsMapsAllFields() {
        com.goshare.trip.entity.TripStop s = new com.goshare.trip.entity.TripStop();
        s.setTripId(5L);
        s.setSeq(1);
        s.setCity("邢台");
        s.setAddress("邢台东站");
        s.setLng(new BigDecimal("114.50"));
        s.setLat(new BigDecimal("37.07"));
        s.setOffsetMinutes(55);
        when(tripStopMapper.selectList(any(Wrapper.class))).thenReturn(List.of(s));

        List<com.goshare.trip.dto.TripStopDTO> stops = service.listStops(5L);

        assertEquals(1, stops.size());
        assertEquals("邢台", stops.get(0).getCity());
        assertEquals(55, stops.get(0).getOffsetMinutes());
        assertEquals(new BigDecimal("37.07"), stops.get(0).getLat());
    }
}
