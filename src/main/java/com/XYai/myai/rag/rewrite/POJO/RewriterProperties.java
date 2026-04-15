package com.XYai.myai.rag.rewrite.POJO;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@NoArgsConstructor
@Component
@ConfigurationProperties(prefix = "rewriter")
public class RewriterProperties {
    /** 开始进行重写的最小字符数阈值，低于该长度将跳过 LLM 重写 */
    int RewriterMinChars;    

    /** 是否启用重写功能（用于在开发或调试时关闭） */
    Boolean RewriterEnabled;    
}
