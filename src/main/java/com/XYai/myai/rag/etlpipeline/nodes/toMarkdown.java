package com.XYai.myai.rag.etlpipeline.nodes;

import com.XYai.myai.rag.etlpipeline.pojo.IngestionContext;
import com.XYai.myai.rag.etlpipeline.pojo.NodeConfig;
import com.XYai.myai.rag.etlpipeline.pojo.NodeResult;

public class toMarkdown implements Ingestion{
    @Override
    public String getNodeType() {
        return "toMarkdown";
    }

    @Override
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        return null;
    }
}
