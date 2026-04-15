package com.XYai.myai.rag.milvus;

import com.XYai.myai.config.Result;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Milvus 向量数据库管理控制器。
 * 提供集合列表、元数据查询、搜索、创建、加载/卸载、删除、重建等接口。
 */
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
        if(allCollectionNames==null||allCollectionNames.isEmpty())return Result.error(400,"数据库查询失败");
        return Result.success(allCollectionNames);
    }

    /**
     * 获取集合数据
     * 
     * @param collectionName 集合名称
     * @return 元数据列表
     */
    @PostMapping("/metadata")
    public Result<List<Map<String, Object>>> getCollectionsMetadata(String collectionName) {
        if (!milvusCollectionService.isLoaded(collectionName))
            return Result.error(404, "集合未加载");
        List<Map<String, Object>> collectionNameMetadata = milvusService.getUserCollectionNameMetadata(collectionName);
        return Result.success(collectionNameMetadata);
    }

    /**
     * 搜索集合
     * 
     * @param str 搜索字符串
     * @return 匹配的集合名称列表
     */
    @PostMapping("/search")
    public Result<List<String>> search(String str) {
        List<String> searchCollectionNames = milvusService.search(str);
        return Result.success(searchCollectionNames);
    }

    /**
     * 创建集合（有参数，使用 POST）
     * 
     * @param collectionName 集合名称
     * @return 结果
     */
    @PostMapping("/create")
    public Result<String> createCollection(String collectionName) {
        milvusCollectionService.createCollectionIfAbsent(collectionName);
        return Result.success();
    }

    /**
     * 获取集合状态（有参数，使用 POST）
     * 
     * @param collectionName 集合名称
     * @return 是否已加载
     */
    @PostMapping("/status")
    public Result<Boolean> getStatus(String collectionName) {
        boolean isLoad = milvusCollectionService.isLoaded(collectionName);
        return Result.success(isLoad);
    }

    /**
     * 加载卸载集合
     * 
     * @param collectionName
     * @return
     * @throws Exception
     */
    @PostMapping("/loadOrunload")
    public Result<String> unloadCollection(String collectionName) throws Exception {
        if (!milvusCollectionService.isLoaded(collectionName)) {
            milvusCollectionService.loadCollection(collectionName);
        } else {
            milvusCollectionService.unloadCollection(collectionName);
        }
        return Result.success();
    }

    /**
     * 删除集合（有参数，使用 POST）
     * 
     * @param collectionName 集合名称
     * @return 结果
     */
    @PostMapping("/delete")
    public Result<String> dropCollection(String collectionName) {
        milvusCollectionService.drop(collectionName);
        return Result.success();
    }

    /**
     * 删除指定文档（有参数，使用 POST）
     * 
     * @param collection 集合名
     * @param kbId       知识库 ID
     * @param fileName   文件名
     * @param chunkId    分块 ID
     * @return 结果
     */
    @PostMapping("/delete/doc")
    public Result<String> dropDocument(String collection, String kbId, String fileName, String chunkId) {
        try {
            milvusService.deleteDocument(collection, kbId, fileName, chunkId);
            return Result.success("删除成功");
        } catch (Exception e) {
            log.error("删除文档失败:", e);
            return Result.error(500, "删除失败: " + e.getMessage());
        }
    }

    /**
     * 重建集合（有参数，使用 POST）
     * 
     * @param collectionName 集合名称
     * @return 结果
     */
    @PostMapping("/rebuild")
    public Result<String> rebuildCollection(String collectionName) {
        milvusCollectionService.rebuild(collectionName);
        return Result.success();
    }

}
