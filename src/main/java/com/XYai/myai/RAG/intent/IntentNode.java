package com.XYai.myai.RAG.intent;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "intent_node", autoResultMap = true)
public class IntentNode {

    /** 唯一标识，展示名称，如「人事」「年假」 - 数据库主键 */
    @TableId(value = "name", type = IdType.INPUT)
    private String name;

    /** id节点名如 "group-hr"、"group-hr-leave-annual" (数据库列 node_id) */
    @TableField("node_id")
    private String nodeId;

    /** 知识库ID（KB类型节点用） */
    @TableField("kb_id")
    private String kbId;


    /** 语义说明，帮助LLM理解分类范围 */
    @TableField("description")
    private String description;

    /* 层级：DOMAIN(领域) / CATEGORY(类目) / TOPIC(话题) */
    //private IntentLevel level;

    /** 父节点名称，根节点为null (数据库列 parent_name) */
    @TableField("parent_name")
    private String parentName;

    /** 示例问题，帮助LLM更精准识别 */
    @TableField(value = "examples", typeHandler = JacksonTypeHandler.class)
    private List<String> examples;

    /** 子节点列表，无子女则为叶子节点 */
    @TableField(exist = false)
    private List<IntentNode> children;

    /* 类型：KB(知识库) / MCP(工具调用) / SYSTEM(系统) */
    //private IntentKind kind;

    /** 向量数据库集合名称（KB类型用） */
    @TableField("collection_name")
    private String collectionName;

    /** MCP工具ID（工具调用类型用） */
    @TableField("mcp_tool_id")
    private String mcpToolId;

    // 其他配置：节点级TopK、Prompt模板等
    @TableField("top_k")
    private Integer topK;

    @TableField("prompt_template")
    private String promptTemplate;

    /** 子节点数量（数据库 children_count） */
    @TableField("children_count")
    private Integer childrenCount;

    /** 创建与更新时间戳（数据库 created_at / updated_at） */
    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}