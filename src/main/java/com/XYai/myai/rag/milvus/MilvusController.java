package com.XYai.myai.rag.milvus;

import com.XYai.myai.config.Result;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/*
  TODO目前为真正的Milvus操作,实现权限隔离
  没有集合权限,不能查找到,便不能进行所有操作
  有集合权限,只能对有权限的文档进行操作
  集合操作:
  增加集合,没有集合创建结合,有增加用户查看集合的权限
  删除集合:多用户拥有权限删除查看权限,单用户删除集合
  重建:多用户拥有权限删除用户的文件权限,单用户重建集合
  卸载:仅更改状态,取消查看集合名以外的操作,注意删除检索时候的权限,卸载后改集合下当前集合的当前用户文件不能查询
  加载:仅更改状态,恢复所有操作
  文档操作:
  单用户权限实施真正操作,多用户权限删除权限
 */


//TODO集合无文件需要进行TTL删除

/**
 * Milvus 向量数据库管理控制器。
 * 提供集合列表、元数据查询、搜索、创建、加载/卸载、删除、重建等接口。
 */
@Slf4j
@RestController
@RequestMapping("/milvus")
public class MilvusController {

    @Resource
    private MilvusAclManager milvusAclManager;
    @Resource
    private MilvusFileManager milvusFileManager;
    @Resource
    private MilvusCollectionService milvusCollectionService;

    /**
     * 查看数据库的数据(实际为redis下user的所有数据)
     *
     * @return 返回数据库集合列表
     */
    @GetMapping("/list")
    public Result<List<String>> listCollections() {
        return Result.success(milvusCollectionService.getAllCollectionNames());
    }

    /**
     * 获取集合数据
     *
     * @param collectionName 集合名称
     * @return 元数据列表
     */
    @PostMapping("/metadata")
    public Result<List<Map<String, Object>>> getCollectionsMetadata(String collectionName) {
        //是否有权限（若没有权限则返回错误）
        if (!milvusAclManager.getCollectionAcl(collectionName)) {
            log.info("getCollectionsMetadataNoACl:{}", collectionName);
            return Result.error(1, "无权限访问");
        }
        if (!milvusAclManager.userCollectionLoadAcl(collectionName)) {
            return Result.error(1, "未加载");
        }
        return Result.success(milvusFileManager.getUserCollectionNameMetadata(collectionName));
    }

    /**
     * 搜索集合
     *
     * @param str 搜索字符串
     * @return 匹配的集合名称列表
     */
    @PostMapping("/search")
    public Result<List<String>> search(String str) {//权限隔离
        List<String> searchCollectionNames = milvusCollectionService.search(str);
        return Result.success(searchCollectionNames);
    }

    /**
     * 创建集合（有参数，使用 POST）
     *
     * @param collectionName 集合名称
     * @return 结果
     */
    @PostMapping("/create")
    public Result<String> createCollection(String collectionName) {//增加权限
        milvusCollectionService.createCollectionIfAbsent(collectionName);
        return Result.success("集合创建成功");
    }

    /**
     * 获取集合状态（有参数，使用 POST）
     *
     * @param collectionName 集合名称
     * @return 是否已加载
     */
    @PostMapping("/status")
    public Result<Boolean> getStatus(String collectionName) {
        //集合文件读取权力（若没有权限则返回错误）
        if (!milvusAclManager.getCollectionAcl(collectionName)) {
            log.info("getStatusNoACl:{}", collectionName);
            return Result.error(1, "无权限访问");
        }
        boolean isLoad = milvusCollectionService.isLoaded(collectionName);
        return Result.success(isLoad);
    }

    /**
     * 加载卸载集合
     *
     * @param collectionName 集合
     * @return 更新
     * @throws Exception 错误
     */
    @PostMapping("/loadOrunload")
    public Result<String> unloadOrLoadCollection(String collectionName) throws Exception {//权限隔离
        //读取权开关（若没有权限则返回错误）
        if (!milvusAclManager.getCollectionAcl(collectionName)) {
            log.info("unloadOrLoadCollectionNoACl:{}", collectionName);
            return Result.error(1, "无权限访问");
        }
        if (!milvusCollectionService.isLoaded(collectionName)) {
            milvusCollectionService.loadCollection(collectionName);
            log.info("loadCollectionName:{}", collectionName);
        } else {
            milvusCollectionService.unloadCollection(collectionName);
            log.info("unLoadCollectionName:{}", collectionName);
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
        if (!milvusAclManager.getCollectionAcl(collectionName)) {
            log.info("dropCollectionNoACl:{}", collectionName);
            return Result.error(1, "无权限访问");
        }
        return milvusCollectionService.drop(collectionName);
    }

    /**
     * 删除指定文档（有参数，使用 POST）
     *
     * @return 结果
     */
    @PostMapping("/delete/doc")
    public Result<String> dropDocument(Long chunkId, String fileId, String collectionName) {
        //验证权限
        log.info("文件:{}集合:{}", fileId, collectionName);
        if (!milvusAclManager.getCollectionAcl(collectionName) || !milvusAclManager.getFileAcl(fileId)) {
            log.info("dropDocumentNoACl:{}", collectionName);
            return Result.error(1, "无权限访问");
        }
        milvusFileManager.deleteDocument(chunkId, fileId, collectionName);
        return Result.success("删除成功");
    }

    /**
     * 重建集合（有参数，使用 POST）
     *
     * @param collectionName 集合名称
     * @return 结果
     */
    @PostMapping("/rebuild")
    public Result<String> rebuildCollection(String collectionName) {
        if (!milvusAclManager.getCollectionAcl(collectionName)) {
            log.info("rebuildCollectionNoACl:{}", collectionName);
            return Result.error(1, "无权限访问");
        }
        return milvusCollectionService.rebuild(collectionName);
    }
}
