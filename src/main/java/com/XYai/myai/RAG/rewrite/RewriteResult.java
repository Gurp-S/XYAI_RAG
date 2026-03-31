package com.XYai.myai.RAG.rewrite;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RewriteResult {
    String rewrittenQuery; //重写后的标准句子
    List<String> subQuery; //拆分的子问题
}