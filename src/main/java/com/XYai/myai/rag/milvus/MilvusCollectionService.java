package com.XYai.myai.rag.milvus;

import com.XYai.myai.config.Result;
import com.XYai.myai.redis.RedisKeyConfig;
import com.XYai.myai.user.LoginUserInfoManager;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Milvus 集合（Collection）管理服务
 * 功能：负责创建、删除、重建、查询 Milvus 集合，并通过 Redis 维护加载/存在状态
 * 用于 RAG 系统中的向量库多集合隔离管理（例如：不同业务/不同文件使用独立集合）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MilvusCollectionService {

    @Resource
    private MilvusAclManager milvusAclManager;
    @Resource
    private RedissonClient redissonClient;

    // ====================== 生命周期,添加创建,删除,重构 ======================

    /**
     * 删除 Milvus 集合
     *
     * @param collectionName 集合名
     * @return 删除结果
     */
    public Result<String> drop(String collectionName) {
        //删除用户的集合权限
        milvusAclManager.deleteCollectionAcl(collectionName);
        //删除用户的集合下所有文件的权限
        milvusAclManager.deleteCollectionDocumentAcl(collectionName);
        return Result.success();
    }

    /**
     * 重建集合 = 先删除 + 再创建
     * 注意：会清空该集合下所有向量数据
     *
     * @param collectionName 集合名
     * @return 重建结果
     */
    public Result<String> rebuild(String collectionName) {
        //删除用户的集合下所有文件的权限
        milvusAclManager.deleteCollectionDocumentAcl(collectionName);
        return Result.success();
    }

    // ====================== 显示创建加载和刷盘 ======================

    /**
     * 对齐 Spring AI 默认 Schema 的显式建表 milvus collection，并按当前项目配置创建索引。
     * 字段固定：doc_id、content、embedding、metadata
     * 注意:存在后用户权限没有的话应该增加权限
     */
    public void createCollectionIfAbsent(String collectionName) {
        // 添加集合权限给当前用户(创建同时加载给用户)
        Long userId = LoginUserInfoManager.getUserId();
        redissonClient.getSet(RedisKeyConfig.userLoadCollectionsKey(userId)).add(collectionName);
        redissonClient.getAtomicLong(RedisKeyConfig.collectionUserCountKey(collectionName)).incrementAndGet();
    }

    /**
     * 显式加载 collection，确保 Attu / 搜索侧能够立即看到并使用数据。
     */
    public void loadCollection(String collectionName) {
        // 简单实现：把 collection 标记为 loaded（从 unloaded 移到 loaded），不触及 MySQL
        milvusAclManager.moveCollectionToLoaded(collectionName);
    }

    /**
     * 显式卸载 collection，释放内存中的已加载集合。
     */
    public void unloadCollection(String collectionName) throws Exception {
        // 简单实现：把 collection 标记为 unloaded（从 loaded 移到 unloaded），不触及 MySQL
        milvusAclManager.moveCollectionToUnloaded(collectionName);
    }

    /**
     * 判断集合是否已加载到内存
     */
    public boolean isLoaded(String collectionName) {
        //获取可读集合的权限
        //再redis权限中存在为加载
        return milvusAclManager.userCollectionLoadAcl(collectionName);
    }

    /**
     * 模糊搜索集合
     *
     * @param str 搜索词
     * @return 搜索结果
     */
    public List<String> search(String str) {
        // 获取所有向量集合名字
        List<String> collectionNames = getAllCollectionNames();
        // 判空
        if (collectionNames.isEmpty())
            return null;

        String strLowerCase = str.toLowerCase();
        return collectionNames.stream()
                .filter(name -> name.toLowerCase().contains(strLowerCase))
                .sorted()
                .toList();
    }

    /**
     * 获取当前用户可见的 Milvus Collection 列表
     */
    public List<String> getAllCollectionNames() {
        // 先读取当前用户权限集合
        return milvusAclManager.getUserCollectionsAcl();
    }

    public Boolean exists(String collectionName) {
        return milvusAclManager.getCollectionAcl(collectionName);
    }

}