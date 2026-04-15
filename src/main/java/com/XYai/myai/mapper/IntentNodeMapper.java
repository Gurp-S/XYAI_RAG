package com.XYai.myai.mapper;

import com.XYai.myai.rag.intent.POJO.IntentNode;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 意图节点 Mapper 接口。
 * 负责管理 RAG 意图判断节点的持久化。
 */
@Mapper
public interface IntentNodeMapper extends BaseMapper<IntentNode> {
}