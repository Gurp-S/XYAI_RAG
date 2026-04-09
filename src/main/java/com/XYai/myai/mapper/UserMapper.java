package com.XYai.myai.mapper;

import com.XYai.myai.User.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户 Mapper 接口。
 * 负责管理用户基本信息及权限配置的持久化。
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
