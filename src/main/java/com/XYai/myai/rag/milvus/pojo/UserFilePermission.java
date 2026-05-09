package com.XYai.myai.rag.milvus.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户 <-> 集合 权限关系
 * 对应数据库表 xy_user_file_permission
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("xy_user_file_permission")
public class UserFilePermission {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;          // 用户ID

    private String collectionName;// 集合名称

    private String permission;    // 权限：READ / WRITE / ADMIN

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}