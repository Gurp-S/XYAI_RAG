package com.XYai.myai.rag.milvus.pojo;

import java.util.Set;

/**
 * 存入 Milvus 的 metadata 字段
 * 所有权限字段必须在这里声明！
 */
public class MilvusMetadata {

    // 必须包含：所有用于过滤查询的字段
    public static final Set<String> METADATA_FIELDS = Set.of(
            // ========== 文本基本信息 ==========
            "fileName",
            "chunkSize",
            "createTime",
            // ========== 结构与父子切片 ==========
            "section_title",
            "section_path",
            "parent_text",
            "chunk_type",
            // ========== 权限字段（核心） ==========
            //"ownerId", //userId 最高权力移除
            //"groupId",移除
            "visibility" //private public groupPublic
            //"sharedWith", //移除
    );

    // 必须包含：所有用于过滤查询的字段
    public static final Set<String> METADATA_SHOW = Set.of(
            "fileName",
            "chunkSize",
            "visibility",
            "createTime"
    );
}