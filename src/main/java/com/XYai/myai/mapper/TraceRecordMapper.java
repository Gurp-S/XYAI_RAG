package com.XYai.myai.mapper;

import com.XYai.myai.rag.aop.annotation.TraceRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

/**
 * 链路追踪记录 Mapper 接口。
 * 负责记录 RAG 处理流程的完整追踪信息。
 */
@Mapper
public interface TraceRecordMapper extends BaseMapper<TraceRecord> {
    @Update("UPDATE trace_record SET status=#{status}, end_time=#{endTime}, cost_time=#{costTime}, error_message=#{errorMessage} WHERE trace_id=#{traceId}")
    int updateByTraceId(@Param("traceId") String traceId,
                        @Param("status") String status,
                        @Param("endTime") LocalDateTime endTime,
                        @Param("costTime") Long costTime,
                        @Param("errorMessage") String errorMessage);
}
