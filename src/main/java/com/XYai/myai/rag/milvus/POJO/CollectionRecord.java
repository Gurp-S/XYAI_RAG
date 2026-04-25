package com.XYai.myai.rag.milvus.POJO;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * collection <-> file 映射与 collection 中已存在分块（xy_collection_file）
 * presentChunks 存 JSON 字符串，例如: [1,2,5]
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("xy_collection_file")
public class CollectionRecord {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("collection_name")
    private String collectionName;

    @TableField("file_id")
    private String fileChunkId;

    @TableField("chunk_size")
    private Integer chunkSize;

    @TableField("present_chunks")
    private String presentChunks;
}