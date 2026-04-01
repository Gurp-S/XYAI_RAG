package com.XYai.myai.RAG.Memory;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@NoArgsConstructor
@Component
@ConfigurationProperties(prefix = "memory")
public class MemoryProperties {

    /** 保留最近 n 轮对话原文，不参与摘要压缩 */
    int HistoryKeepTurns;

    /** 当对话轮数达到该值时触发摘要压缩 */
    int SummaryStartTurns;

    /** 单个摘要允许的最大字符数限制 */
    int SummaryMaxChars;

    /** 是否启用摘要压缩功能 */
    Boolean SummaryEnabled;
}
