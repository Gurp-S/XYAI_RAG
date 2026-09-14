package com.XYai.myai.rag.channel.pojo;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
@Component
@org.springframework.boot.context.properties.ConfigurationProperties(prefix = "rag.retrieval")
public class RetrievalProperties {
    /** 是否启用重排（保留兼容旧配置；rerankProvider=off 等效 false） */
    @Builder.Default
    private Boolean rerankLLM = true;

    // ==================== 安全：ACL 查询层下推 ====================
    /** 是否在 Milvus 查询层下推 ACL 过滤（检索后过滤保留为纵深防御） */
    @Builder.Default
    private boolean aclPushdownEnabled = true;
    /** 下推 expr 中文件级 like 子句上限，超出则放弃下推（避免 expr 超长），由后置过滤兜底 */
    @Builder.Default
    private int aclPushdownMaxFileClauses = 200;

    // ==================== 召回后过滤阈值 ====================
    /** 双通道均需超过的通用阈值（BM25 为 max 归一分，除第一名外均 <1，不宜过严） */
    @Builder.Default
    private double commonScoreThreshold = 0.5;
    @Builder.Default
    private double highScoreThreshold = 0.85;
    @Builder.Default
    private double lowScoreThreshold = 0.2;
    /** 纯向量召回（无 BM25 分）阈值：0.6 过严，rerank 前会误杀相关候选 */
    @Builder.Default
    private double vectorOnlyThreshold = 0.5;
    /** BM25 单独阈值：用于 highAndLow 组合判断中的低门槛 */
    @Builder.Default
    private double bm25ScoreThreshold = 0.35;
    /** 重排分数绝对下限：与相对门槛取大者生效 */
    @Builder.Default
    private double minRerankScore = 0.1;
    /** 重排相对门槛比例：cutoff = top分数 × 该值 */
    @Builder.Default
    private double rerankGateRelative = 0.3;
    /** 门槛过滤后保底保留条数（防误杀相关块） */
    @Builder.Default
    private int rerankGateKeepTop = 4;
    @Builder.Default
    private int minContentLength = 10;
    @Builder.Default
    private int maxContentLength = 3000;

    // ==================== 召回/精排深度 ====================
    /** 单通道召回 TopK（业界基准 50-75，受双通道×双子查询放大，取 20 平衡延迟） */
    @Builder.Default
    private int channelTopK = 20;
    /** 精排后最终保留 TopK（送入 LLM 上下文的 chunk 数） */
    @Builder.Default
    private int finalTopK = 8;

    // ==================== 分块粒度（父子切片 small-to-big） ====================
    /**
     * 子块最大 token 数。旧默认 4096 过粗：单块语义被稀释，向量召回区分度差
     * （黄金集失败用例根因）。父子切片就绪后子块可安全细化，业界基准 256~512。
     */
    @Builder.Default
    private int maxChunkSize = 512;
    /** 子块间句子级重叠 token 数，避免答案被切割边界截断 */
    @Builder.Default
    private int chunkOverlap = 64;
    /** 小块合并阈值（token）：低于该值的相邻块向前合并 */
    @Builder.Default
    private int minMergeSize = 32;
    /** 语义分割相似度阈值：相邻句余弦低于该值时切分 */
    @Builder.Default
    private double semanticSplitThreshold = 0.6;

    // ==================== 重排（Rerank） ====================
    /** 重排服务提供方：dashscope（百炼 gte-rerank）/ ollama（本地）/ off（仅混合分数降级） */
    @Builder.Default
    private String rerankProvider = "dashscope";
    /**
     * 上下文感知重排（Contextual Reranking）：重排输入前拼「文档名+章节路径」，
     * 让 rerank 模型感知文档角色（实施/研究等），同主题多文档区分更准。
     * 参考 Anthropic Contextual Retrieval 在重排段的延伸用法。
     */
    @Builder.Default
    private boolean rerankContextualInput = true;

    // ==================== 检索质量门控（CRAG 式） ====================
    /** 是否启用检索质量门控：低于阈值触发一次改写重检索 */
    @Builder.Default
    private boolean qualityGateRetryEnabled = true;
    /** 门控阈值：证据分低于该值视为证据不足（0~1） */
    @Builder.Default
    private double qualityGateThreshold = 0.45;
    /** 支撑块判定线：融合分 ≥ 该值的块计入支撑数（防单块侥幸过闸） */
    @Builder.Default
    private double qualityGateSupportFloor = 0.3;
    /** 支撑块目标数：达到该数量支撑时证据分不再衰减 */
    @Builder.Default
    private int qualityGateSupportTarget = 3;

    // ==================== 元数据加权（文档标题/章节路径） ====================
    /** 是否启用元数据加权：查询词命中 fileName/section_path 的候选获得分数加成 */
    @Builder.Default
    private boolean metadataBoostEnabled = true;
    /** 元数据加权强度：final = min(1, score + weight × 查询词覆盖率)，业界实践 0.05~0.15 */
    @Builder.Default
    private double metadataBoostWeight = 0.08;

    // ==================== 文档级整合（跨文档置信度门控） ====================
    /** 是否启用：按来源文档分组，弱源文档候选分数 < 最优文档分×ratio 时剔除 */
    @Builder.Default
    private boolean docConsolidationEnabled = true;
    /** 跨文档保留比例：非最优文档候选须 ≥ 最优文档代表分 × 该值才保留（1=不整合） */
    @Builder.Default
    private double docKeepRatio = 0.8;

    // ==================== 分数落差自适应截断（elbow，Adaptive-RAG 思路） ====================
    /** 是否启用分数落差截断：topK 内相邻分数最大落差超过阈值时提前截断，剔除长尾噪声 */
    @Builder.Default
    private boolean scoreElbowEnabled = true;
    /** 触发截断的最小分数落差（0~1），默认 0.12 */
    @Builder.Default
    private double scoreElbowGap = 0.12;

    // ==================== 父子切片 ====================
    /** 生成前是否将选中子块展开为章节全文（small-to-big） */
    @Builder.Default
    private boolean parentExpandEnabled = true;
}
