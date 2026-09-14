package com.XYai.myai.rag.milvus;

import com.XYai.myai.commonUtils.redis.RedisKeyConfig;
import com.XYai.myai.config.Result;
import com.XYai.myai.user.LoginUserInfoManager;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * Milvus 集合（Collection）管理服务
 * 功能：负责创建、删除、重建、查询 Milvus 集合，并通过 Redis 维护加载/存在状态
 * 用于 RAG 系统中的向量库多集合隔离管理（例如：不同业务/不同文件使用独立集合）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MilvusCollectionManager {

    @Resource
    private MilvusAclManager milvusAclManager;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public Result<String> drop(String collectionName) {
        milvusAclManager.deleteCollectionAcl(collectionName);
        milvusAclManager.deleteCollectionDocumentAcl(collectionName);
        return Result.success();
    }

    public Result<String> rebuild(String collectionName) {
        milvusAclManager.deleteCollectionDocumentAcl(collectionName);
        return Result.success();
    }


    public void createCollectionIfAbsent(String collectionName) {
        Long userId = LoginUserInfoManager.getUserId();
        stringRedisTemplate.opsForSet().add(RedisKeyConfig.userLoadCollectionsKey(userId), collectionName);
        stringRedisTemplate.opsForValue().increment(RedisKeyConfig.collectionUserCountKey(collectionName));
    }

    /**
     * 显式加载 collection，确保 Attu / 搜索侧能够立即看到并使用数据。
     */
    public void loadCollection(String collectionName) {
        milvusAclManager.moveCollectionToLoaded(collectionName);
    }

    /**
     * 显式卸载 collection，释放内存中的已加载集合。
     */
    public void unloadCollection(String collectionName) throws Exception {
        milvusAclManager.moveCollectionToUnloaded(collectionName);
    }

    /**
     * 判断集合是否已加载到内存
     */
    public boolean isLoaded(String collectionName) {
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
        Set<String> collectionNames = getAllCollectionNames();
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
    public Set<String> getAllCollectionNames() {
        return milvusAclManager.getUserCollectionsAcl();
    }

    public Boolean exists(String collectionName) {
        return milvusAclManager.getCollectionAcl(collectionName);
    }

    public Integer getCollectionsFileNumber(String collectionName) {
        Long size = stringRedisTemplate.opsForSet().size(RedisKeyConfig.collectionFileIds(collectionName));
        return size != null ? size.intValue() : 0;
    }
}