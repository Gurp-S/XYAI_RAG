package com.XYai.myai.RAG.pipeline;

import com.XYai.myai.RAG.DocumentChunk;
import com.XYai.myai.Exception.PipelineException;

import java.util.List;

/**
 * 文档入库流水线节点接口。
 */
@FunctionalInterface
public interface PipelineNode {

    /**
     * 执行节点处理逻辑。
     *
     * @param input 原始输入文档
     * @param ctx 流水线共享上下文
     * @return 节点产出的文档分块
     * @throws PipelineException 节点执行失败时抛出
     */
    List<DocumentChunk> process(DocumentIn input, PipelineContext ctx) throws PipelineException;
}

