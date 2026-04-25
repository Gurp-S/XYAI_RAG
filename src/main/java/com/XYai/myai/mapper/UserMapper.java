package com.XYai.myai.mapper;

import com.XYai.myai.user.POJO.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 用户 Mapper 接口。
 * 负责管理用户基本信息及权限配置的持久化。
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

    @Select("select case when user_id = #{userId} then friend_id else user_id end as friend_id " +
            "from xy_user_friend where user_id = #{userId} or friend_id = #{userId}")
    List<Long> selectFriendIds(@Param("userId") Long userId);

    @Insert("insert ignore into xy_user_friend(user_id, friend_id, create_time) " +
            "values(LEAST(#{userId}, #{friendId}), GREATEST(#{userId}, #{friendId}), now())")
    int insertFriendRelation(@Param("userId") Long userId, @Param("friendId") Long friendId);

    @Delete("delete from xy_user_friend where pair_low_id = LEAST(#{userId}, #{friendId}) " +
            "and pair_high_id = GREATEST(#{userId}, #{friendId})")
    int deleteFriendRelation(@Param("userId") Long userId, @Param("friendId") Long friendId);
}
