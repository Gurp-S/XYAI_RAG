package com.XYai.myai.rag.milvus.POJO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 文档权限主体信息
 * 与 Milvus 向量数据的 metadata 一一绑定
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileMilvus {
    private Long groupId;      // 组ID（团队/部门ID）
    private Long ownerId;      // 上传者用户ID（唯一隔离键）
    private List<Long> sharedWith;   // 共享给某些用户
    private String visibility;   // 可见范围：private(私有) / group(组内) / public(公共)
}