package com.goshare.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.goshare.order.entity.IdempotencyRecord;

/**
 * 幂等记录数据访问
 */
public interface IdempotencyRecordMapper extends BaseMapper<IdempotencyRecord> {
}
