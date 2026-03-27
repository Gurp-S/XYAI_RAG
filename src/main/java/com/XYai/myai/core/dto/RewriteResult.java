package com.XYai.myai.core.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class RewriteResult {
    String intent;  //识别出的意图类型普通回答或者检索Knowledge_Search|General_Chat
    String rewritten_query; //重写后的标准句子
    List<String> sub_query; //拆分的子问题
}