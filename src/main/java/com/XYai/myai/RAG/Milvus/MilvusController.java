package com.XYai.myai.RAG.Milvus;

import com.XYai.myai.Config.Result;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/milvus")
public class MilvusController {

    @Resource
    private MilvusService milvusService;

    /**
     * 查看数据库的数据
     * 
     * @return 返回数据库集合列表
     */
    @GetMapping("/list")
    public Result<List<String>> listCollections() {
        List<String> allCollectionNames = milvusService.getAllCollectionNames();
        return Result.success(allCollectionNames);
    }

    @GetMapping("/metadata")
    public Result<List<Map<String,Object>>> getCollectionsMetadata(String collectionName) {
        List<Map<String, Object>> collectionNameMetadata = milvusService.getCollectionNameMetadata(collectionName);
        log.info(collectionNameMetadata.toString());
        return Result.success(collectionNameMetadata);
    }
}
