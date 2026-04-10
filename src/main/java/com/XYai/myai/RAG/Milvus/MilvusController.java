package com.XYai.myai.RAG.Milvus;

import com.XYai.myai.Config.Result;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/milvus")
public class MilvusController {

    @Resource
    private MilvusService milvusService;
    @Resource
    private MilvusCollectionService milvusCollectionService;

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
        return Result.success(collectionNameMetadata);
    }

    @GetMapping("/search")
    public Result<List<String>> search(String str) {
        List<String> searchCollectionNames = milvusService.search(str);
        return Result.success(searchCollectionNames);
    }

    @PutMapping("/create")
    public Result<String> createCollection(String collectionName){
        milvusCollectionService.createCollectionIfAbsent(collectionName);
        return Result.success();
    }

    @DeleteMapping("/delete")
    public Result<String> dropCollection(String collectionName){
        milvusCollectionService.drop(collectionName);
        return Result.success();
    }

    @RequestMapping("/rebuild")
    public Result<String> rebuildCollection(String collectionName){
        milvusCollectionService.rebuild(collectionName);
        return Result.success();
    }

}
