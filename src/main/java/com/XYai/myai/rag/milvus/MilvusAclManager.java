package com.XYai.myai.rag.milvus;

import cn.hutool.core.util.StrUtil;
import com.XYai.myai.commonUtils.redis.RedisBitSetUtils;
import com.XYai.myai.commonUtils.redis.RedisKeyConfig;
import com.XYai.myai.rag.graph.Neo4jKnowledgeGraphService;
import com.XYai.myai.user.LoginUserInfoManager;
import com.github.benmanes.caffeine.cache.Cache;
import io.milvus.client.MilvusClient;
import io.milvus.param.dml.DeleteParam;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Component
public class MilvusAclManager {

    private static final String LUA_SETBIT_INCR = """
            \
            local granted = 0
            for i = 1, #ARGV do
              local idx = tonumber(ARGV[i])
              local was = redis.call('GETBIT', KEYS[1], idx)
              if was == 0 then
                redis.call('SETBIT', KEYS[1], idx, 1)
                redis.call('INCR', KEYS[i + 1])
                granted = granted + 1
              end
            end
            return granted
            """;

    private static final DefaultRedisScript<Long> LUA_SETBIT_INCR_SCRIPT = new DefaultRedisScript<>(LUA_SETBIT_INCR,
            Long.class);

    private static final String LUA_SETBIT_INCR_WITH_COLLECTION = """
            \
            local granted = 0
            for i = 1, #ARGV do
              local idx = tonumber(ARGV[i])
              local was = redis.call('GETBIT', KEYS[1], idx)
              if was == 0 then
                redis.call('SETBIT', KEYS[1], idx, 1)
                redis.call('INCR', KEYS[i + 2])
                granted = granted + 1
              end
              redis.call('SETBIT', KEYS[2], idx, 1)
            end
            return granted
            """;

    private static final DefaultRedisScript<Long> LUA_SETBIT_INCR_WITH_COLLECTION_SCRIPT = new DefaultRedisScript<>(
            LUA_SETBIT_INCR_WITH_COLLECTION, Long.class);

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private Neo4jKnowledgeGraphService neo4jKnowledgeGraphService;

    @Resource
    private MilvusClient milvusClient;

    @Value("${spring.ai.vectorstore.milvus.collectionName:my_ai}")
    private String physicalCollectionName;

    @Value("${spring.ai.vectorstore.milvus.databaseName:my_xy}")
    private String databaseName;

    @Resource
    @Qualifier("defaultCache")
    private Cache<String, Object> localCache;

    /**
     * 获取当前用户可见的集合列表（跨实例共享，Redis 优先）。
     */
    public List<String> getUserCollectionsAcl() {
        Long userId = LoginUserInfoManager.getUserId();
        String loadRedisKey = RedisKeyConfig.userLoadCollectionsKey(userId);
        String unloadRedisKey = RedisKeyConfig.userUnloadCollectionsKey(userId);
        Set<String> permissionLoadSet = stringRedisTemplate.opsForSet().members(loadRedisKey);
        Set<String> permissionUnloadSet = stringRedisTemplate.opsForSet().members(unloadRedisKey);
        if (permissionUnloadSet == null)
            permissionUnloadSet = Set.of();
        if (permissionLoadSet == null)
            permissionLoadSet = Set.of();
        return Stream.concat(
                permissionLoadSet.stream(),
                permissionUnloadSet.stream()).collect(Collectors.toList());
    }

    /**
     * 获取当前权限集合元数据(文档id)
     * 使用批量 bitmap 读取替代逐位 GETBIT 以减少 Redis roundtrip。
     */
    public List<String> getCollectionFiles(String collectionName) {
        Long userId = LoginUserInfoManager.getUserId();
        List<String> result = new ArrayList<>();
        Set<String> fileIdSet = stringRedisTemplate.opsForSet()
                .members(RedisKeyConfig.collectionFileIds(collectionName));

        for (String entry : fileIdSet) {
            if (entry == null || !entry.contains(":"))
                continue;
            String[] parts = entry.split(":", 2);
            String fileId = parts[0];
            int chunkSize = Integer.parseInt(parts[1]);
            if (chunkSize <= 0)
                continue;

            String userFileBitKey = RedisKeyConfig.userFileBitKey(userId, fileId);
            String collectionFileChunkBitKey = RedisKeyConfig.collectionFileChunkBitKey(collectionName, fileId);

            byte[][] raw = stringRedisTemplate.execute((RedisCallback<byte[][]>) conn -> {
                byte[] u = conn.stringCommands().get(userFileBitKey.getBytes(StandardCharsets.UTF_8));
                byte[] c = conn.stringCommands().get(collectionFileChunkBitKey.getBytes(StandardCharsets.UTF_8));
                return new byte[][] { u, c };
            });
            if (raw == null || raw[0] == null || raw[1] == null)
                continue;

            BitSet userFileChunks = RedisBitSetUtils.bitSetFromRedisBytes(raw[0]);
            BitSet collectionFileChunks = RedisBitSetUtils.bitSetFromRedisBytes(raw[1]);
            log.info("用户权限位图 (userFileChunks): {}", bitsToString(userFileChunks, chunkSize));
            log.info("集合权限位图 (collectionFileChunks): {}", bitsToString(collectionFileChunks, chunkSize));
            userFileChunks.and(collectionFileChunks);
            log.info("交集位图: {}", bitsToString(userFileChunks, chunkSize));
            for (int i = 1; i <= chunkSize; ++i) {
                if (userFileChunks.get(i)) {
                    result.add(fileId + ":" + i);
                    log.info("集合内分块:{}", i);
                }
            }
        }
        return result;
    }

    private String bitsToString(BitSet bits, int max) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 1; i <= max; i++) {
            sb.append(bits.get(i) ? "1" : "0");
            if (i < max)
                sb.append(", ");
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * 获取集合的权限
     */
    public Boolean getCollectionAcl(String collectionName) {
        List<String> permissionSet = getUserCollectionsAcl();
        if (permissionSet == null)
            return false;
        return permissionSet.stream().anyMatch(name -> StrUtil.equals(name, collectionName));
    }

    public Boolean getFileAcl(String fileId) {
        Long userId = LoginUserInfoManager.getUserId();
        if (fileId == null || fileId.isBlank())
            return false;
        try {
            byte[] raw = stringRedisTemplate.execute((RedisCallback<byte[]>) conn -> conn.stringCommands()
                    .get(RedisKeyConfig.userFileBitKey(userId, fileId).getBytes(StandardCharsets.UTF_8)));
            return raw != null && RedisBitSetUtils.bitSetFromRedisBytes(raw).cardinality() > 0;
        } catch (Exception e) {
            log.warn("getFileAcl bitset error", e);
            return false;
        }
    }

    public Boolean getFileChunkAcl(String fileId, int chunkId) {
        Long userId = LoginUserInfoManager.getUserId();
        if (fileId == null || fileId.isBlank())
            return false;
        try {
            byte[] raw = stringRedisTemplate.execute((RedisCallback<byte[]>) conn -> conn.stringCommands()
                    .get(RedisKeyConfig.userFileBitKey(userId, fileId).getBytes(StandardCharsets.UTF_8)));
            if (raw == null)
                return false;
            return RedisBitSetUtils.bitSetFromRedisBytes(raw).get(chunkId);
        } catch (Exception e) {
            log.warn("getFileChunkAcl error", e);
            return false;
        }
    }

    /**
     * 删除该集合下所有文件当前用户的权限
     */
    public void deleteCollectionDocumentAcl(String collectionName) {
        Long userId = LoginUserInfoManager.getUserId();
        Set<String> collectionFileIds = stringRedisTemplate.opsForSet()
                .members(RedisKeyConfig.collectionFileIds(collectionName));

        Set<String> fileIdsInCollection = collectionFileIds.stream()
                .filter(Objects::nonNull)
                .map(str -> str.split(":", 2))
                .filter(parts -> parts.length >= 1)
                .map(parts -> parts[0])
                .collect(Collectors.toSet());

        for (String fileId : fileIdsInCollection) {
            try {
                stringRedisTemplate.delete(RedisKeyConfig.userFileBitKey(userId, fileId));
            } catch (Exception e) {
                log.debug("删除用户文件位图失败", e);
            }
        }
    }

    /**
     * 删除 Redis 里的集合下文件权限缓存
     */
    public void deleteDocumentAcl(String collectionName, int chunkId, String fileId) {
        Long userId = LoginUserInfoManager.getUserId();
        if (collectionName == null || collectionName.isBlank() || fileId == null || fileId.isBlank()) {
            return;
        }
        // 单集合单用户->删除milvus文件和集合权限和用户权限
        // 多集合单用户->删除集合权限
        // 单集合多用户->删除该用户权限和集合权限
        // 多集合多用户->删除集合权限
        boolean singleCollection = true;
        try {
            Set<String> collections = stringRedisTemplate.opsForSet()
                    .members(RedisKeyConfig.fileHashKey(fileId));
            if (collections != null) {
                for (String coll : collections) {
                    if (coll == null || coll.isBlank()) {
                        continue;
                    }
                    if (!coll.equals(collectionName)) {
                        singleCollection = false;
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("读取 file->collection 映射失败 fileId={}", fileId, e);
        }

        boolean hadAcl = false;

        if (singleCollection) {
            String userBitKey = RedisKeyConfig.userFileBitKey(userId, fileId);
            try {
                byte[] raw = stringRedisTemplate.execute((RedisCallback<byte[]>) conn -> conn.stringCommands()
                        .get(userBitKey.getBytes(StandardCharsets.UTF_8)));
                if (raw != null) {
                    BitSet localBits = RedisBitSetUtils.bitSetFromRedisBytes(raw);
                    hadAcl = localBits.get(chunkId);
                    if (hadAcl) {
                        localBits.set(chunkId, false);
                        if (localBits.cardinality() == 0) {
                            stringRedisTemplate.delete(userBitKey);
                        } else {
                            final byte[] finalBytes = RedisBitSetUtils.bitSetToRedisBytes(localBits);
                            stringRedisTemplate.execute((RedisCallback<Object>) conn -> {
                                conn.stringCommands().set(userBitKey.getBytes(StandardCharsets.UTF_8), finalBytes);
                                return null;
                            });
                        }
                    }
                }
            } catch (Exception e) {
                log.error("更新chunk权限失败", e);
                return;
            }
        }

        // 删除当前集合下的文件权限缓存
        try {
            String collBitKey = RedisKeyConfig.collectionFileChunkBitKey(collectionName, fileId);
            byte[] collRaw = stringRedisTemplate.execute((RedisCallback<byte[]>) conn -> conn
                    .stringCommands().get(collBitKey.getBytes(StandardCharsets.UTF_8)));
            BitSet collBits = collRaw != null ? RedisBitSetUtils.bitSetFromRedisBytes(collRaw) : new BitSet();
            collBits.set(chunkId, false);
            if (collBits.cardinality() == 0) {
                // 删除 collectionFileIds 中的 fileId:chunkSize 项
                Set<String> fileIdSet = stringRedisTemplate.opsForSet()
                        .members(RedisKeyConfig.collectionFileIds(collectionName));
                Optional<String> entry = fileIdSet.stream()
                        .filter(s -> s != null && s.startsWith(fileId + ":")).findFirst();
                entry.ifPresent(e -> stringRedisTemplate.opsForSet()
                        .remove(RedisKeyConfig.collectionFileIds(collectionName), e));
                stringRedisTemplate.delete(collBitKey);
                // 移除 file -> collection 映射
                stringRedisTemplate.opsForSet().remove(RedisKeyConfig.fileHashKey(fileId), collectionName);
                Long remaining = stringRedisTemplate.opsForSet()
                        .size(RedisKeyConfig.fileHashKey(fileId));
                if (remaining != null && remaining == 0) {
                    stringRedisTemplate.delete(RedisKeyConfig.fileHashKey(fileId));
                }
            } else {
                final byte[] finalCollBytes = RedisBitSetUtils.bitSetToRedisBytes(collBits);
                stringRedisTemplate.execute((RedisCallback<Object>) conn -> {
                    conn.stringCommands().set(collBitKey.getBytes(StandardCharsets.UTF_8), finalCollBytes);
                    return null;
                });
            }
        } catch (Exception ignore) {
        }

        // 只有一个集合时才进行计数与真实删除
        if (singleCollection && hadAcl) {
            String countKey = RedisKeyConfig.fileChunkUserCountKey(fileId, chunkId);
            long fileChunkUserCount = 0;
            try {
                Long val = stringRedisTemplate.opsForValue().decrement(countKey);
                fileChunkUserCount = val != null ? val : -1;
                if (fileChunkUserCount <= 0) {
                    if (fileChunkUserCount < 0) {
                        stringRedisTemplate.opsForValue().set(countKey, "0");
                    }
                    deleteNoAclFileChunk(fileId, chunkId);
                    String docId = null;
                    try {
                        docId = fileId + ":" + String.format("%06d", chunkId);
                        neo4jKnowledgeGraphService.deleteChunkRelations(Collections.singleton(docId));
                    } catch (Exception e) {
                        log.error("清理 Neo4j chunk {} 失败，文件 {} chunk {}", docId, fileId, chunkId, e);
                    }
                    try {
                        stringRedisTemplate.delete(countKey);
                    } catch (Exception ignore) {
                    }
                }
            } catch (Exception e) {
                log.error("更新 fileChunk 计数器失败 fileId={} chunkId={}", fileId, chunkId, e);
            }
        }
    }

    public void deleteNoAclFileChunk(String fileId, int chunkId) {
        // 构建
        String expectedId = fileId + ":" + String.format("%06d", chunkId);
        String expr = String.format("doc_id == \"%s\"", expectedId);
        milvusClient.delete(DeleteParam.newBuilder()
                .withDatabaseName(databaseName)
                .withCollectionName(physicalCollectionName)
                .withExpr(expr)
                .build());
    }

    public void addFileUserACl(String fileId, String collectionName, int chunkSize) {
        Long userId = LoginUserInfoManager.getUserId();
        int cap = (int) Math.min(chunkSize, RedisKeyConfig.MAX_CHUNK_PER_FILE);
        log.info("userid={},collectionName={},fileId={},chunkSize={}", userId, collectionName, fileId, chunkSize);
        List<Integer> all = new ArrayList<>(cap);
        for (int i = 1; i <= cap; i++) {
            all.add(i);
        }

        setUserFileChunks(userId, fileId, all, chunkSize);
        if (cap > 0) {
            BitSet collBits = new BitSet(cap + 1);
            for (int i = 1; i <= cap; i++) {
                collBits.set(i);
            }
            byte[] allOnes = RedisBitSetUtils.bitSetToRedisBytes(collBits);
            String collKey = RedisKeyConfig.collectionFileChunkBitKey(collectionName, fileId);
            try {
                stringRedisTemplate.execute((RedisCallback<Object>) conn -> conn.stringCommands()
                        .set(collKey.getBytes(StandardCharsets.UTF_8), allOnes));
            } catch (Exception e) {
                log.warn("设置 collectionBitSet 失败 collection={} fileId={}", collectionName, fileId, e);
            }
        }
        stringRedisTemplate.opsForSet().add(RedisKeyConfig.collectionFileIds(collectionName), fileId + ":" + chunkSize);
        try {
            stringRedisTemplate.opsForSet().add(RedisKeyConfig.fileHashKey(fileId), collectionName);
        } catch (Exception ignore) {
        }
    }

    public void addFileUserACl(List<Document> documents, String collectionName) {
        if (documents == null || documents.isEmpty())
            return;
        Long userId = LoginUserInfoManager.getUserId();
        log.info("用户:{},文档添加权限:{},集合:{}", userId, documents.stream().map(Document::getId).toList(), collectionName);
        Document firstDoc = documents.getFirst();
        String docId = firstDoc.getId();
        String fileId = docId.substring(0, docId.lastIndexOf(":"));
        int chunkSize = Integer.parseInt(firstDoc.getMetadata().get("chunkSize").toString());

        List<Integer> chunkIds = documents.stream()
                .map(doc -> Integer.parseInt(doc.getId().substring(doc.getId().lastIndexOf(":") + 1)))
                .toList();
        log.info("chunkIds:{}", chunkIds);
        setUserFileChunks(userId, fileId, chunkIds, chunkSize);
        if (!chunkIds.isEmpty()) {
            String collKey = RedisKeyConfig.collectionFileChunkBitKey(collectionName, fileId);
            byte[] collKeyBytes = collKey.getBytes(StandardCharsets.UTF_8);
            int maxCap = (int) Math.min(chunkSize, RedisKeyConfig.MAX_CHUNK_PER_FILE);
            try {
                List<Object> results = stringRedisTemplate.executePipelined((RedisCallback<Object>) conn -> {
                    for (Integer id : chunkIds) {
                        if (id == null || id < 1 || id > maxCap)
                            continue;
                        conn.stringCommands().setBit(collKeyBytes, id, true);
                    }
                    return null;
                });
                for (Object obj : results) {
                    if (obj instanceof Exception) {
                        log.error("Pipeline 命令执行失败", (Exception) obj);
                    }
                }
            } catch (Exception e) {
                log.warn("设置 collectionBitSet 失败 collection={} fileId={}", collectionName, fileId, e);
            }
        }
        stringRedisTemplate.opsForSet().add(RedisKeyConfig.collectionFileIds(collectionName), fileId + ":" + chunkSize);
        // 记录 file -> collection 的映射
        try {
            stringRedisTemplate.opsForSet().add(RedisKeyConfig.fileHashKey(fileId), collectionName);
        } catch (Exception ignore) {
        } // 设置后立即验证
    }

    /**
     * 设置用户对某文件的 chunk 权限位（1-based）
     * 批量读取 + Pipeline 写入，替代逐位 GETBIT/SETBIT/INCR。
     */
    private void setUserFileChunks(Long userId, String fileId, Collection<Integer> chunkIds, int chunkSize) {
        int cap = (int) Math.min(chunkSize, RedisKeyConfig.MAX_CHUNK_PER_FILE);
        if (cap <= 0)
            return;

        String userKey = RedisKeyConfig.userFileBitKey(userId, fileId);

        byte[] raw = stringRedisTemplate.execute(
                (RedisCallback<byte[]>) conn -> conn.stringCommands().get(userKey.getBytes(StandardCharsets.UTF_8)));
        BitSet localBits = (raw != null) ? RedisBitSetUtils.bitSetFromRedisBytes(raw) : new BitSet();

        List<Integer> newChunks = new ArrayList<>();
        for (Integer id : chunkIds) {
            if (id == null || id < 1 || id > cap)
                continue;
            if (!localBits.get(id))
                newChunks.add(id);
        }
        if (newChunks.isEmpty())
            return;

        List<String> keys = new ArrayList<>(1 + newChunks.size());
        keys.add(userKey);
        for (Integer id : newChunks) {
            keys.add(RedisKeyConfig.fileChunkUserCountKey(fileId, id));
        }
        List<String> args = new ArrayList<>(newChunks.size());
        for (Integer id : newChunks) {
            args.add(String.valueOf(id));
        }
        stringRedisTemplate.execute(LUA_SETBIT_INCR_SCRIPT, keys, args.toArray());
    }

    public void grantFileChunksToUser(Long userId, String fileId, String collectionName,
            List<Integer> chunkIds, int chunkSize) {
        int cap = (int) Math.min(chunkSize, RedisKeyConfig.MAX_CHUNK_PER_FILE);
        if (cap <= 0 || chunkIds == null || chunkIds.isEmpty()) {
            return;
        }

        List<Integer> validChunks = new ArrayList<>(chunkIds.size());
        for (Integer id : chunkIds) {
            if (id == null || id < 1 || id > cap)
                continue;
            validChunks.add(id);
        }
        if (validChunks.isEmpty())
            return;

        String userBitKey = RedisKeyConfig.userFileBitKey(userId, fileId);
        String collBitKey = RedisKeyConfig.collectionFileChunkBitKey(collectionName, fileId);

        List<String> keys = new ArrayList<>(2 + validChunks.size());
        keys.add(userBitKey);
        keys.add(collBitKey);
        for (Integer id : validChunks) {
            keys.add(RedisKeyConfig.fileChunkUserCountKey(fileId, id));
        }

        List<String> args = new ArrayList<>(validChunks.size());
        for (Integer id : validChunks) {
            args.add(String.valueOf(id));
        }

        stringRedisTemplate.execute(LUA_SETBIT_INCR_WITH_COLLECTION_SCRIPT, keys, args.toArray());
        log.info("授权用户分块: userId={} fileId={} chunks={}", userId, fileId, validChunks.size());
    }

    public boolean userCollectionLoadAcl(String collectionName) {
        Long userId = LoginUserInfoManager.getUserId();
        try {
            return Boolean.TRUE.equals(stringRedisTemplate.opsForSet()
                    .isMember(RedisKeyConfig.userLoadCollectionsKey(userId), collectionName));
        } catch (Exception e) {
            log.error("userCollectionLoadAcl 错误", e);
            return false;
        }
    }

    /**
     * 原子地将 collection 从 unloaded 移到 loaded
     */
    public boolean moveCollectionToLoaded(String collectionName) {
        Long userId = LoginUserInfoManager.getUserId();
        String loadKey = RedisKeyConfig.userLoadCollectionsKey(userId);
        String unloadKey = RedisKeyConfig.userUnloadCollectionsKey(userId);

        try {
            Boolean moved = stringRedisTemplate.opsForSet().move(unloadKey, collectionName, loadKey);
            if (Boolean.TRUE.equals(moved))
                return true;

            Boolean exists = stringRedisTemplate.opsForSet().isMember(loadKey, collectionName);
            if (Boolean.TRUE.equals(exists))
                return true;

            stringRedisTemplate.opsForSet().add(loadKey, collectionName);
            localCache.invalidate("user:fileIdSet:" + userId);
            return true;
        } catch (Exception e) {
            log.error("moveCollectionToLoaded failed", e);
            return false;
        }
    }

    /**
     * 原子地将 collection 从 loaded 移到 unloaded
     */
    public boolean moveCollectionToUnloaded(String collectionName) {
        Long userId = LoginUserInfoManager.getUserId();
        String loadKey = RedisKeyConfig.userLoadCollectionsKey(userId);
        String unloadKey = RedisKeyConfig.userUnloadCollectionsKey(userId);

        try {
            Boolean moved = stringRedisTemplate.opsForSet().move(loadKey, collectionName, unloadKey);
            if (Boolean.TRUE.equals(moved))
                return true;

            Boolean exists = stringRedisTemplate.opsForSet().isMember(unloadKey, collectionName);
            if (Boolean.TRUE.equals(exists))
                return true;

            stringRedisTemplate.opsForSet().add(unloadKey, collectionName);
            localCache.invalidate("user:fileIdSet:" + userId);
            return true;
        } catch (Exception e) {
            log.error("moveCollectionToUnloaded failed", e);
            return false;
        }
    }

    public void deleteCollectionAcl(String collectionName) {
        Long userId = LoginUserInfoManager.getUserId();
        try {
            // 全局用户计数器 - 减 1
            Long newCountVal = stringRedisTemplate.opsForValue()
                    .decrement(RedisKeyConfig.collectionUserCountKey(collectionName));
            long newCount = newCountVal != null ? newCountVal : -1;

            // 清理当前用户的 load/unload set 中该 collection
            try {
                stringRedisTemplate.opsForSet().remove(RedisKeyConfig.userLoadCollectionsKey(userId), collectionName);
                stringRedisTemplate.opsForSet().remove(RedisKeyConfig.userUnloadCollectionsKey(userId), collectionName);
            } catch (Exception ignore) {
            }

            // 如果全局计数为 0 清理 collection 相关的全局数据
            if (newCount <= 0) {
                try {
                    // 删除集合下文件索引及计数器，并清理集合内每个文件的 collectionBitSet
                    Set<String> fileIds = stringRedisTemplate.opsForSet()
                            .members(RedisKeyConfig.collectionFileIds(collectionName));
                    for (String entry : fileIds) {
                        try {
                            if (entry != null && entry.contains(":")) {
                                String fid = entry.split(":", 2)[0];
                                stringRedisTemplate
                                        .delete(RedisKeyConfig.collectionFileChunkBitKey(collectionName, fid));
                            }
                        } catch (Exception ignore) {
                        }
                    }
                    stringRedisTemplate.delete(RedisKeyConfig.collectionFileIds(collectionName));
                    stringRedisTemplate.delete(RedisKeyConfig.collectionUserCountKey(collectionName));
                } catch (Exception ex) {
                    log.warn("删除: {} 集合失败", collectionName, ex);
                }
            }
        } catch (Exception e) {
            log.error("删除集合失败", e);
        }
    }
}