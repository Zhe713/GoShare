package com.goshare.trip.es;

import com.goshare.trip.entity.Trip;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.CriteriaQuery;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 行程 ES 检索：文档同步/删除降级不抛 / 条件搜索（起终点+状态+日期范围）
 */
class TripEsServiceTest {

    private ElasticsearchOperations operations;
    private TripEsService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        operations = mock(ElasticsearchOperations.class);
        service = new TripEsService(operations);
    }

    private Trip trip() {
        Trip t = new Trip();
        t.setId(5L);
        t.setFromCity("石家庄");
        t.setToCity("邯郸");
        t.setDepartureTime(LocalDateTime.now().plusDays(1));
        t.setStatus(0);
        return t;
    }

    @Test
    void indexSavesDocument() {
        service.index(trip());
        verify(operations).save(any(TripDocument.class));
    }

    @Test
    void indexFailureSwallowed() {
        doThrow(new RuntimeException("es down")).when(operations).save(any(TripDocument.class));
        assertDoesNotThrow(() -> service.index(trip()));
    }

    @Test
    void removeDeletesDocument() {
        service.remove(5L);
        verify(operations).delete("5", TripDocument.class);
    }

    @Test
    void removeFailureSwallowed() {
        doThrow(new RuntimeException("es down"))
                .when(operations).delete(eq("5"), eq(TripDocument.class));
        assertDoesNotThrow(() -> service.remove(5L));
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchWithDateReturnsMappedHits() {
        TripDocument doc = new TripDocument();
        doc.setId(5L);
        doc.setFromCity("石家庄");
        SearchHit<TripDocument> hit = mock(SearchHit.class);
        when(hit.getContent()).thenReturn(doc);
        SearchHits<TripDocument> hits = mock(SearchHits.class);
        when(hits.getSearchHits()).thenReturn(List.of(hit));
        when(operations.search(any(CriteriaQuery.class), eq(TripDocument.class))).thenReturn(hits);

        List<TripDocument> list = service.search("石家庄", "邯郸", LocalDate.now());

        assertEquals(1, list.size());
        assertEquals(5L, list.get(0).getId());
    }

    @Test
    void searchWithoutDateOmitsTimeCriteria() {
        @SuppressWarnings("unchecked")
        SearchHits<TripDocument> hits = mock(SearchHits.class);
        when(hits.getSearchHits()).thenReturn(List.of());
        when(operations.search(any(CriteriaQuery.class), eq(TripDocument.class))).thenReturn(hits);

        List<TripDocument> list = service.search("石家庄", "邯郸", null);

        assertTrue(list.isEmpty());
    }
}
