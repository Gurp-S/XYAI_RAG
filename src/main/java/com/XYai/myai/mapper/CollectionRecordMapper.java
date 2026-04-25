package com.XYai.myai.mapper;

import com.XYai.myai.rag.milvus.POJO.CollectionRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * CollectionRecord Mapper。
 * 提供 collection <-> file 的基础 CRUD 操作，由 MyBatis-Plus 接管。
 */
@Mapper
public interface CollectionRecordMapper extends BaseMapper<CollectionRecord> {

}

