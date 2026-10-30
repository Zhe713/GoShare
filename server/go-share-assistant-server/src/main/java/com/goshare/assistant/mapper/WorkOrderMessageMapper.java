package com.goshare.assistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.goshare.assistant.entity.WorkOrderMessage;
import org.apache.ibatis.annotations.Mapper;

/**
 * 工单消息 Mapper
 */
@Mapper
public interface WorkOrderMessageMapper extends BaseMapper<WorkOrderMessage> {
}
