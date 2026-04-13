package com.XYai.myai.User.POJO;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 用户基础信息实体类。
 * 映射数据库 `user` 表，存储用户账号、密码及基本信息。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_user")
public class User {
    /** 数据库主键 ID */
    @TableId(value = "id")
    private Long id;

    @TableField("group_id")
    private String groupId;

    @TableField(exist = false)
    private List<String> friends;

    @TableField(exist = false)
    private List<String> groups;

    @TableField("user_rank")
    private Long rank;//0 管理,1 组织管理,2 使用者

    private String name;

    /** 用户密码（请在生产环境中采用加密存储） */
    private String password;
}