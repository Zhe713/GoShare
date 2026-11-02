package com.goshare.assistant.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.goshare.assistant.dto.WorkOrderAnalyzeVO;
import com.goshare.assistant.entity.WorkOrder;
import com.goshare.assistant.entity.WorkOrderMessage;
import com.goshare.assistant.entity.LlmCallLog;
import com.goshare.assistant.mapper.LlmCallLogMapper;
import com.goshare.assistant.mapper.WorkOrderMapper;
import com.goshare.assistant.mapper.WorkOrderMessageMapper;
import com.goshare.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * B1 工单：关键词分类 / 会话摘要提炼 / 分类模板草稿 / 摘要只触发一次幂等
 */
class WorkOrderServiceTest {

    private WorkOrderMapper workOrderMapper;
    private WorkOrderMessageMapper messageMapper;
    private LlmCallLogMapper llmCallLogMapper;
    private StringRedisTemplate redisTemplate;
    private WorkOrderService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        workOrderMapper = mock(WorkOrderMapper.class);
        messageMapper = mock(WorkOrderMessageMapper.class);
        llmCallLogMapper = mock(LlmCallLogMapper.class);
        redisTemplate = mock(StringRedisTemplate.class);
        service = new WorkOrderService(workOrderMapper, messageMapper, llmCallLogMapper, redisTemplate);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);
    }

    private WorkOrder order() {
        WorkOrder o = new WorkOrder();
        o.setId(1L);
        o.setOrderNo("20261030151932907564");
        o.setCategory("退款");
        o.setStatus("OPEN");
        return o;
    }

    @Test
    void analyzeOrderNotFound() {
        when(workOrderMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.analyze("20261030151932907564"));
        assertEquals(404, ex.getCode());
    }

    @Test
    void analyzeFirstTimeGeneratesSummaryAndDraft() {
        when(workOrderMapper.selectOne(any(Wrapper.class))).thenReturn(order());
        WorkOrderMessage m1 = new WorkOrderMessage();
        m1.setId(1L);
        m1.setWorkOrderId(1L);
        m1.setContent("取消订单会扣钱吗");
        WorkOrderMessage m2 = new WorkOrderMessage();
        m2.setId(2L);
        m2.setWorkOrderId(1L);
        m2.setContent("我要退款");
        when(messageMapper.selectList(any(Wrapper.class))).thenReturn(List.of(m1, m2));

        WorkOrderAnalyzeVO vo = service.analyze("20261030151932907564");

        assertTrue(vo.getFirstTime());
        assertEquals("退款", vo.getCategory());
        assertTrue(vo.getSummary().contains("2 条消息"));
        assertTrue(vo.getSummary().contains("我要退款"));
        // 退款模板草稿含订单号
        assertTrue(vo.getReplyDraft().contains("20261030151932907564"));
        assertTrue(vo.getReplyDraft().contains("违约金"));
        // 首次：工单状态置 PROCESSING + 记账（未调大模型）
        ArgumentCaptor<WorkOrder> wCaptor = ArgumentCaptor.forClass(WorkOrder.class);
        verify(workOrderMapper).updateById(wCaptor.capture());
        assertEquals("PROCESSING", wCaptor.getValue().getStatus());
        ArgumentCaptor<LlmCallLog> lCaptor = ArgumentCaptor.forClass(LlmCallLog.class);
        verify(llmCallLogMapper).insert(lCaptor.capture());
        assertEquals(1, lCaptor.getValue().getGateSkipped());
    }

    @Test
    void analyzeSecondTimeReturnsStoredSummary() {
        WorkOrder o = order();
        o.setSummary("已生成的摘要");
        o.setReplyDraft("已生成的草稿");
        when(workOrderMapper.selectOne(any(Wrapper.class))).thenReturn(o);
        // 幂等键已存在 → 不重复生成
        when(redisTemplate.opsForValue().setIfAbsent(anyString(), anyString(), any())).thenReturn(false);

        WorkOrderAnalyzeVO vo = service.analyze("20261030151932907564");

        assertFalse(vo.getFirstTime());
        assertEquals("已生成的摘要", vo.getSummary());
        verify(workOrderMapper, never()).updateById(any(WorkOrder.class));
        verify(llmCallLogMapper, never()).insert(any(LlmCallLog.class));
    }

    @Test
    void classifyByKeywords() {
        // createMock：首次查不到 → insert 建单 → analyze 再次查询返回刚建的工单
        WorkOrder[] holder = {null};
        when(workOrderMapper.selectOne(any(Wrapper.class))).thenAnswer(inv -> holder[0]);
        when(workOrderMapper.insert(any(WorkOrder.class))).thenAnswer(inv -> {
            WorkOrder o = inv.getArgument(0);
            o.setId(2L);
            holder[0] = o;
            return 1;
        });
        when(messageMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        // 退款关键词
        WorkOrderAnalyzeVO vo = service.createMock("20261030151932907564",
                List.of("订单 20261030151932907564 我要退款"));
        assertEquals("退款", vo.getCategory());
        // 候补关键词（重置，避免分类串扰）
        holder[0] = null;
        WorkOrderAnalyzeVO vo2 = service.createMock("20261030151932907565",
                List.of("没座位了，帮我候补一下"));
        assertEquals("候补", vo2.getCategory());
        // 无关键词 → 其他
        holder[0] = null;
        WorkOrderAnalyzeVO vo3 = service.createMock("20261030151932907566",
                List.of("你好，在吗"));
        assertEquals("其他", vo3.getCategory());
    }

    @Test
    void summaryTruncatesLongLatestMessage() {
        when(workOrderMapper.selectOne(any(Wrapper.class))).thenReturn(order());
        StringBuilder longMsg = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            longMsg.append("很长很长的咨询内容");
        }
        WorkOrderMessage m = new WorkOrderMessage();
        m.setId(1L);
        m.setWorkOrderId(1L);
        m.setContent(longMsg.toString());
        when(messageMapper.selectList(any(Wrapper.class))).thenReturn(List.of(m));

        WorkOrderAnalyzeVO vo = service.analyze("20261030151932907564");

        assertTrue(vo.getSummary().contains("…"));
        assertTrue(vo.getSummary().length() < longMsg.length() + 40);
    }
}
