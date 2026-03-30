package com.XYai.myai.Memory;

import com.XYai.myai.Chat.ChatMessage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SummaryMessage {
    String lastestSummary;
    String chatMessage;
}
