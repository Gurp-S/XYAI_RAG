package com.XYai.myai;

import com.XYai.myai.mapper.IntentNodeMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class MyAiApplicationTests {

    @Autowired
    private IntentNodeMapper intentNodeMapper;


    /**
     * 一次性执行：从数据库读取所有意图节点，生成 Embedding 并存入 Milvus。
     * 运行该测试即可完成同步，后续意图识别可直接使用向量检索。
     */
    @Test
    public void storeIntentNodesToVectorStore() {

    }
}