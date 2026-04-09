package com.XYai.myai.RAG.Milvus;

import com.XYai.myai.Config.Result;
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
     * 
     * @return 返回数据库集合列表
     */
    @GetMapping("/list")
    public Result<List<String>> listCollections() {
        List<String> allCollectionNames = milvusService.getAllCollectionNames();
        return Result.success(allCollectionNames);
    }

    @GetMapping("/metadata")
    public Result<List<String>> getCollectionsMetadata(String collectionName) {
        milvusService.getCollectionNameMetadata(collectionName);
        return Result.success();
    }
}
