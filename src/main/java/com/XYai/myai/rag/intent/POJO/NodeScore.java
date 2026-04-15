package com.XYai.myai.rag.intent.POJO;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NodeScore {
    String intentNodeName;
    Double score;
}