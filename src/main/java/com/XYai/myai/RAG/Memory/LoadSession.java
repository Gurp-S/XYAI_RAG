package com.XYai.myai.RAG.Memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoadSession {
    //摘要
    String summary;
    //上下文
    Set<String> conversation;
}
