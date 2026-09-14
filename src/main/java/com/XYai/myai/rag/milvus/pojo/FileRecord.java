package com.XYai.myai.rag.milvus.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 数据库存储：文件主记录
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("xy_file_record")
public class FileRecord {

    @TableId(value = "file_chunk_id", type = IdType.INPUT)
    private String fileChunkId;

    @TableField("use_count")
    private Long fileUsingCount;

    @TableField("file_name")
    private String fileName;
}