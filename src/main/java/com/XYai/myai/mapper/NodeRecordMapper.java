package com.XYai.myai.mapper;

import com.XYai.myai.rag.aop.annotation.NodeRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

/**
 * 链路执行节点记录 Mapper 接口。
 * 负责记录 RAG 链路中各个节点的执行详情。
 */
@Mapper
public interface NodeRecordMapper extends BaseMapper<NodeRecord> {
    @Insert("INSERT INTO node_record(node_id, trace_id, node_name, node_type, status, start_time) VALUES(#{nodeId}, #{traceId}, #{nodeName}, #{nodeType}, #{status}, #{startTime})")
    int insert(NodeRecord record);

    @Select("SELECT COALESCE(AVG(cost_time), 0) FROM node_record WHERE cost_time IS NOT NULL")
    Long selectAvgCostTime();

    @Update("UPDATE node_record SET status=#{status}, end_time=#{endTime}, cost_time=#{costTime}, error_message=#{errorMessage} WHERE node_id=#{nodeId}")
    int updateNodeStatus(@Param("nodeId") String nodeId,
                         @Param("status") String status,
                         @Param("endTime") LocalDateTime endTime,
                         @Param("costTime") Long costTime,
                         @Param("errorMessage") String errorMessage);
}
