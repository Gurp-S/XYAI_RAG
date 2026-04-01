package com.XYai.myai.User;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

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
