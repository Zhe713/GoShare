package com.goshare.assistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.goshare.assistant.entity.LlmCallLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * LLM 调用记账 Mapper
 */
@Mapper
public interface LlmCallLogMapper extends BaseMapper<LlmCallLog> {
}
