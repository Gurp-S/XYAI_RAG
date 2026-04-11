package com.XYai.myai.RAG.ETLpipeline.Nodes;

import com.XYai.myai.RAG.Aop.Annotation.RagTraceNode;
import com.XYai.myai.RAG.ETLpipeline.POJO.IngestionContext;
import com.XYai.myai.RAG.ETLpipeline.POJO.NodeConfig;
import com.XYai.myai.RAG.ETLpipeline.POJO.NodeResult;

public interface Ingestion {

    String getNodeType();

    NodeResult execute(IngestionContext context, NodeConfig config);
}
