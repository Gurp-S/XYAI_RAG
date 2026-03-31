package com.XYai.myai.RAG.Memory;

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
