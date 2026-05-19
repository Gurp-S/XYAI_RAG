package com.XYai.myai.rag.memory.pojo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@NoArgsConstructor
@Component
@Builder
@AllArgsConstructor
public class MemoryProperties {

    /**
     * 保留最近 n 轮对话原文，不参与摘要压缩
     */
    @Builder.Default
    private int historyKeepTurns = 3;

    /**
     * 当对话轮数达到该值时触发摘要压缩
     */
    @Builder.Default
    private int summaryStartTurns = 5;

    /**
     * 单个摘要允许的最大字符数限制
     */
    @Builder.Default
    private int summaryMaxChars = 2000;

    /**
     * 是否启用摘要压缩功能
     */
    @Builder.Default
    private Boolean SummaryEnabled = true;
}