package com.XYai.myai.rewrite;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@NoArgsConstructor
@Component
@ConfigurationProperties(prefix = "rewriter")
public class RewriterProperties {
    int RewriterMinChars;	//开始重写最小字符数，避免摘要本身占用过多Token
    Boolean RewriterEnabled;	//是否启用摘要功能，开发环境关闭便于调试历史消息
}
