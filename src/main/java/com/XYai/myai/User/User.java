package com.XYai.myai.User;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户基础信息实体类。
 * 映射数据库 `user` 表，存储用户账号、密码及基本信息。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("user")
public class User {
    /** 数据库主键 ID */
    @TableId
    private Long id;

    /** 用户密码（请在生产环境中采用加密存储） */
    private String password;
}