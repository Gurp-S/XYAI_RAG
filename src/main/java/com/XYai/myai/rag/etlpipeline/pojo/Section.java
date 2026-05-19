package com.XYai.myai.rag.etlpipeline.pojo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Section {
    String title;               // 当前章节标题（最贴近的标题）
    List<String> breadcrumbs;   // 从根到当前的所有标题路径
    int level;                  // 标题层级，1 为最高
    String content;             // 该章节下的非标题文本
    List<Section> children;     // 子章节
}