package com.XYai.myai.mapper;

import com.XYai.myai.RAG.Aop.Annotation.TraceRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 链路追踪记录 Mapper 接口。
 * 负责记录 RAG 处理流程的完整追踪信息。
 */
@Mapper
public interface TraceRecordMapper extends BaseMapper<TraceRecord> {
}
