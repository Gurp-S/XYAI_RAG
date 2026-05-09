package com.XYai.myai.rag.etlpipeline.nodes;

import com.XYai.myai.rag.etlpipeline.pojo.IngestionContext;
import com.XYai.myai.rag.etlpipeline.pojo.NodeConfig;
import com.XYai.myai.rag.etlpipeline.pojo.NodeResult;

public interface Ingestion {

    String getNodeType();

    NodeResult execute(IngestionContext context, NodeConfig config);
}
