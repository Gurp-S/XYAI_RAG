package com.XYai.myai.mapper;

import com.XYai.myai.rag.milvus.pojo.FileRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文件主记录 Mapper。
 * 仅提供基础 CRUD，由 MyBatis-Plus 接管。
 */
@Mapper
public interface FileRecordMapper extends BaseMapper<FileRecord> {
}

