package com.XYai.myai.User.POJO;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Builder;
import lombok.Data;

import java.util.List;


@Data
@Builder
@TableName("t_group")
public class Group {

    @TableId(value = "group_id")
    String groupId;

    @TableField(exist = false)
    List<User> group;
}
