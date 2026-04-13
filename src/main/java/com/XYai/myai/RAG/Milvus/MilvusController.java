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

    /**
     * 获取集合数据
     * @param collectionName
     * @return
     */
    @GetMapping("/metadata")
    public Result<List<Map<String,Object>>> getCollectionsMetadata(String collectionName) {
        if(!milvusCollectionService.isLoaded(collectionName))return Result.error(404,"集合未加载");
        List<Map<String, Object>> collectionNameMetadata = milvusService.getCollectionNameMetadata(collectionName);
        return Result.success(collectionNameMetadata);
    }

    /**
     * 搜索集合
     * @param str
     * @return
     */
    @GetMapping("/search")
    public Result<List<String>> search(String str) {
        List<String> searchCollectionNames = milvusService.search(str);
        return Result.success(searchCollectionNames);
    }

    /**
     * 创建集合
     * @param collectionName
     * @return
     */
    @PutMapping("/create")
    public Result<String> createCollection(String collectionName){
        milvusCollectionService.createCollectionIfAbsent(collectionName);
        return Result.success();
    }

    /**
     * 获取集合状态
     * @param collectionName
     * @return
     */
    @GetMapping("/status")
    public Result<Boolean> getStatus(String collectionName){
        boolean isLoad = milvusCollectionService.isLoaded(collectionName);
        return Result.success(isLoad);
    }

    /**
     * 加载卸载集合
     * @param collectionName
     * @return
     * @throws Exception
     */
    @PostMapping("/loadOrunload")
    public Result<String> unloadCollection(String collectionName) throws Exception {
        if(!milvusCollectionService.isLoaded(collectionName)){
            milvusCollectionService.loadCollection(collectionName);
        }else{
            milvusCollectionService.unloadCollection(collectionName);
        }
        return Result.success();
    }

    /**
     * 删除集合
     * @param collectionName
     * @return
     */
    @DeleteMapping("/delete")
    public Result<String> dropCollection(String collectionName){
        milvusCollectionService.drop(collectionName);
        return Result.success();
    }

    /**
     * 删除指定文档
     * @param collection
     * @param kbId
     * @param chunkId
     * @return
     */
    @DeleteMapping("/delete/doc")
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
     * 重建集合
     * @param collectionName
     * @return
     */
    @RequestMapping("/rebuild")
    public Result<String> rebuildCollection(String collectionName){
        milvusCollectionService.rebuild(collectionName);
        return Result.success();
    }

}
