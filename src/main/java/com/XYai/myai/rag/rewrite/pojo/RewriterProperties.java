package com.XYai.myai.rag.rewrite.pojo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@NoArgsConstructor
@Builder
@AllArgsConstructor
@Component
public class RewriterProperties {
    /**
     * 开始进行重写的最小字符数阈值，低于该长度将跳过 LLM 重写
     */
   @Builder.Default
    private int RewriterMinChars = 20;

    /**
     * 是否启用重写功能（用于在开发或调试时关闭）
     */
    @Builder.Default
    private Boolean RewriterEnabled = true;
}