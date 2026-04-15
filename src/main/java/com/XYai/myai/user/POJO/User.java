package com.XYai.myai.user.POJO;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户基础信息实体类
 * 映射数据库 xy_user 表
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("xy_user")
public class User {

    /** 主键ID */
    @TableId(value = "id")
    private Long id;

    /** 组织/群组ID */
    @TableField("group_id")
    private String groupId;

    /** 角色等级：0 管理员 1 组织管理员 2 普通用户 */
    @TableField("user_rank")
    private Long userRank;

    /** 用户名 */
    private String name;

    /** 密码（密文） */
    private String password;

    /** 头像URL */
    @TableField("avatar")
    private String avatar;

    /** 账号状态：true启用 false禁用 */
    private Boolean status;


    /** 手机号（唯一） */
    @TableField("phone")
    private String phone;

    /** 邮箱 */
    @TableField("email")
    private String email;

    /** 创建时间 */
    @TableField("create_time")
    private LocalDateTime createTime;

    /** 更新时间 */
    @TableField("update_time")
    private LocalDateTime updateTime;

    /** 最后登录时间 */
    @TableField("last_login_time")
    private LocalDateTime lastLoginTime;

    /** 账号备注/描述 */
    @TableField("remark")
    private String remark;

    // ====================== 非数据库字段 ======================
    @TableField(exist = false)
    private List<String> friends;

    @TableField(exist = false)
    private List<String> groups;
}