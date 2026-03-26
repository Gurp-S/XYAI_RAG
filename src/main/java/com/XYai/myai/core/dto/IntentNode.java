package com.XYai.myai.core.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class IntentNode {
    /** 唯一标识，如 "group-hr"、"group-hr-leave-annual" */
    private String id;

    /** 知识库ID（KB类型节点用） */
    private String kbId;

    /** 展示名称，如「人事」「年假」 */
    private String name;

    /** 语义说明，帮助LLM理解分类范围 */
    private String description;

    /* 层级：DOMAIN(领域) / CATEGORY(类目) / TOPIC(话题) */
    //private IntentLevel level;

    /** 父节点ID，根节点为null */
    private String parentId;

    /** 示例问题，帮助LLM更精准识别 */
    private List<String> examples;

    /** 子节点列表，无子女则为叶子节点 */
    private List<IntentNode> children;

    /* 类型：KB(知识库) / MCP(工具调用) / SYSTEM(系统) */
    //private IntentKind kind;

    /** 向量数据库集合名称（KB类型用） */
    private String collectionName;

    /** MCP工具ID（工具调用类型用） */
    private String mcpToolId;

    // 其他配置：节点级TopK、Prompt模板等
    private Integer topK;
    private String promptTemplate;
}