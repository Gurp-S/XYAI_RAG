package com.XYai.myai.mapper;

import com.XYai.myai.User.POJO.Group;
import com.XYai.myai.User.POJO.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface GroupMapper extends BaseMapper<Group> {

    @Select("select id, group_id, user_rank, name from t_user where group_id = #{groupId} order by id")
    List<User> selectMembersByGroupId(@Param("groupId") String groupId);
}
