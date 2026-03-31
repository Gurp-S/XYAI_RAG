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

    int HistoryKeepTurns;	//保留最近n轮对话原文，保证近期交互的连贯性，无需压缩
    int SummaryStartTurns;	//当对话总轮数达到n轮时，开始对超出n轮的部分进行摘要压缩
    int SummaryMaxChars;	//单个摘要的最大字符数，避免摘要本身占用过多Token
    Boolean SummaryEnabled;	//是否启用摘要压缩功能，开发环境关闭便于调试历史消息
}
