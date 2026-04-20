package com.XYai.myai.rag.milvus.POJO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 集合全局权限配置
 * 作用：控制【整个集合】的开放策略、默认可见规则
 * 业务前提：一个集合 存储 多用户数据，靠 metadata 行级隔离
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor//用于文件去重,并存储文件权限(redis)
public class FilePermission {

    private String collectionName;

    private String fileId;

    /**
     * 集合默认可见级别
     * private / public / group
     */
    private String visibility;
}