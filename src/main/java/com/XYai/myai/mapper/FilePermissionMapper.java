package com.XYai.myai.mapper;

import com.XYai.myai.rag.milvus.pojo.FilePermission;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文件权限 Mapper。
 * 仅提供基础 CRUD，由 MyBatis-Plus 接管。
 */
@Mapper
public interface FilePermissionMapper extends BaseMapper<FilePermission> {
}

