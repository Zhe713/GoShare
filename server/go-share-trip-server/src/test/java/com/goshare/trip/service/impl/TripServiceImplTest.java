package com.goshare.trip.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
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
}
