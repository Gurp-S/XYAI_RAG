package com.XYai.myai.rag.evaluate.pojo;

import com.XYai.myai.rag.chat.pojo.ChatMessage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SystemEvaluate {
    private Long conversationId;
    private ChatMessage message;
    private List<String> retrievedChunks;
    private Long latencyMs;
    private String userQuestion;
}
