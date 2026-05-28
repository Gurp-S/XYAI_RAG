package com.XYai.myai.mapper;

import com.XYai.myai.user.pojo.Group;
import com.XYai.myai.user.pojo.User;
import com.XYai.myai.xyAdmin.pojo.GroupMemberCount;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface GroupMapper extends BaseMapper<Group> {

    @Select("select id, group_id, user_rank, name from xy_user where group_id = #{groupId} order by id")
    List<User> selectMembersByGroupId(@Param("groupId") String groupId);

    @Select("SELECT g.group_id, COUNT(u.id) as member_count FROM xy_user_group g LEFT JOIN xy_user u ON u.group_id = g.group_id GROUP BY g.group_id")
    List<GroupMemberCount> selectGroupMemberCounts();
}
