package com.XYai.myai.user.pojo;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;


@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("xy_user_group")
public class Group {

    @TableId(value = "group_id")
    String groupId;

    @TableField(exist = false)
    List<User> group;
}
