package com.XYai.myai.rag.milvus.POJO;

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

    @TableId(value = "file_id", type = IdType.INPUT)
    private String fileId;

    @TableField("file_name")
    private String fileName;

    @TableField("collection_name")
    private String collectionName;

    @TableField("kb_id")
    private String kbId;          // 知识库ID

    @TableField("owner_id")
    private String ownerId;

    @TableField("group_id")
    private String groupId;

    @TableField("visibility")
    private String visibility;

    @TableField("create_time")
    private LocalDateTime createTime;
}