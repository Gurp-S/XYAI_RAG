package com.XYai.myai.rag.milvus.POJO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 数据库存储：文件-权限关联
 * 可扩展用户权限、过期时间、读写权限
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FilePermission {
    private Long fileId;            // 文件ID
    private String userId;          // 授权用户ID
    private String permissionType;  // read/write
    private Long expireTime;        // 过期时间
}