package com.XYai.myai.rag.ragPojo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.security.core.context.SecurityContext;

/**
 * 用户上下文信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserContext {

    /** 用户ID */
    private Long userId;

    /** 安全上下文 */
    private SecurityContext securityContext;
}