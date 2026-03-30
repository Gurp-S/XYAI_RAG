package com.XYai.myai.rewrite;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class RewriteResult {
    String rewrittenQuery; //重写后的标准句子
    List<String> subQuery; //拆分的子问题
}