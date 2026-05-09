package com.XYai.myai.user.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户基础信息实体类
 * 映射数据库 xy_user 表
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserDTO {

    /**
     * 主键ID
     */
    private Long id;

    /**
     * 组织/群组ID
     */
    private String groupId;

    /**
     * 用户名
     */
    private String name;

    /**
     * 密码（密文）
     */
    private String password;

    /**
     * 头像URL
     */
    private String avatar;

    /**
     * 手机号（唯一）
     */
    private String phone;

    /**
     * 邮箱
     */
    private String email;
}