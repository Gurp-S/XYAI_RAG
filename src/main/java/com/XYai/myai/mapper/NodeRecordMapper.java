package com.XYai.myai.mapper;

import com.XYai.myai.RAG.Aop.Annotation.NodeRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 链路执行节点记录 Mapper 接口。
 * 负责记录 RAG 链路中各个节点的执行详情。
 */
@Mapper
public interface NodeRecordMapper extends BaseMapper<NodeRecord> {
}
