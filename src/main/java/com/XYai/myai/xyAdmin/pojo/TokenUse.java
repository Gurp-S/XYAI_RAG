package com.XYai.myai.xyAdmin.pojo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenUse {
    private Long conversationId;
    private Long chatMessageId;
    private int promptTokens;
    private int completionTokens;
    private Long userId;
    private Long costMs;
    private String modelName;
    private String callType;
}
