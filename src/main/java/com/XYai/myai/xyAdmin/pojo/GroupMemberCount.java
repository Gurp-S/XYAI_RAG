package com.XYai.myai.xyAdmin.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GroupMemberCount {
    private String groupId;
    private Long memberCount;
}
