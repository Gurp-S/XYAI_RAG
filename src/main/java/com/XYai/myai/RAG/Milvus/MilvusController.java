package com.XYai.myai.RAG.Milvus;


import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequestMapping("/milvus")
public class MilvusController {

    @Resource
    private MilvusService milvusService;

    /**
     * 查看数据库的数据
     * @return
     */
    @GetMapping("/list")
    public List<String> listCollections() {
        milvusService.getAllCollectionNames();
        return List.of();
    }
}
