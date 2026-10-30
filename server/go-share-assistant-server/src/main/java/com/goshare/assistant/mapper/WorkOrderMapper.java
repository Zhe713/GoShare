package com.goshare.assistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.goshare.assistant.entity.WorkOrder;
import org.apache.ibatis.annotations.Mapper;

/**
 * 工单 Mapper
 */
@Mapper
public interface WorkOrderMapper extends BaseMapper<WorkOrder> {
}
