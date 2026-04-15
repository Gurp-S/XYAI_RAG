package com.XYai.myai.rag.etlpipeline.Nodes;

import com.XYai.myai.rag.etlpipeline.POJO.IngestionContext;
import com.XYai.myai.rag.etlpipeline.POJO.NodeConfig;
import com.XYai.myai.rag.etlpipeline.POJO.NodeResult;

public interface Ingestion {

    String getNodeType();

    NodeResult execute(IngestionContext context, NodeConfig config);
}
