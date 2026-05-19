package com.XYai.myai.rag.graph;

import com.XYai.myai.commonUtils.IK.IKAnalyzerTokenize;
import com.XYai.myai.rag.graph.pojo.CommunitySearchResult;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

@Slf4j
@Service
public class Neo4jKnowledgeGraphService {

    private static final long MIN_BUILD_INTERVAL_MS = 30 * 60 * 1000;
    private static final int TRIPLE_BATCH_SIZE = 5000;

    private final Cache<String, String> neighborSummaryCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterAccess(1, TimeUnit.HOURS)
            .build();
    // 社区维护并发控制
    private final AtomicBoolean maintenanceRunning = new AtomicBoolean(false);
    private final Lock maintenanceLock = new ReentrantLock();
    @Resource
    private Neo4jClient neo4jClient;
    @Resource
    private IKAnalyzerTokenize ikAnalyzerTokenize;
    @Resource
    private EntityRecognizer entityRecognizer;
    @Resource(name = "neo4jExecutor")
    private ThreadPoolTaskExecutor neo4jExecutor;
    private volatile long lastCommunityBuildTime = 0;

    // ==================== 定时任务与触发 ====================

    public void scheduledGraphMaintenance() {
        long communityCount = runLeidenCommunityDetection();
        if (communityCount > 1) {
            buildCommunityNodes();
        } else {
            log.info("社区数量不足（{}），跳过社区摘要构建", communityCount);
        }
    }

    public void triggerCommunityMaintenanceIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastCommunityBuildTime < MIN_BUILD_INTERVAL_MS) {
            return;
        }
        if (maintenanceRunning.compareAndSet(false, true)) {
            maintenanceLock.lock();
            try {
                // 双重检查，防止在获取锁期间已被其他线程执行
                if (now - lastCommunityBuildTime >= MIN_BUILD_INTERVAL_MS) {
                    scheduledGraphMaintenance();
                    lastCommunityBuildTime = System.currentTimeMillis();
                }
            } finally {
                maintenanceLock.unlock();
                maintenanceRunning.set(false);
            }
        }
    }

    // ==================== 三元组批量写入（分片防溢出） ====================

    public void batchInsertTriples(Map<String, String> chunkTriplesMap) {
        if (chunkTriplesMap.isEmpty()) {
            return;
        }

        List<Map<String, Object>> currentBatch = new ArrayList<>(TRIPLE_BATCH_SIZE);
        Set<String> newEntityNames = new HashSet<>();

        for (Map.Entry<String, String> entry : chunkTriplesMap.entrySet()) {
            String chunkId = entry.getKey();
            String triplesText = entry.getValue();
            for (String line : triplesText.split("\n")) {
                String[] parts = line.split("\\|");
                if (parts.length < 3) {
                    continue;
                }
                String head = parts[0].trim();
                String relation = parts[1].trim();
                String tail = parts[2].trim();

                newEntityNames.add(head);
                newEntityNames.add(tail);

                Map<String, Object> row = new HashMap<>();
                row.put("head", head);
                row.put("relation", esc(relation));
                row.put("tail", tail);
                row.put("chunkId", chunkId);
                currentBatch.add(row);

                if (currentBatch.size() >= TRIPLE_BATCH_SIZE) {
                    executeTripleBatch(currentBatch);
                    currentBatch.clear();
                }
            }
        }

        if (!currentBatch.isEmpty()) {
            executeTripleBatch(currentBatch);
        }

        // 异步更新实体词库，避免阻塞主流程
        if (!newEntityNames.isEmpty()) {
            List<String> words = new ArrayList<>(newEntityNames);
            neo4jExecutor.execute(() -> {
                ikAnalyzerTokenize.addWords(words);
                entityRecognizer.refreshEntities();
            });
        }
    }

    private void executeTripleBatch(List<Map<String, Object>> batch) {
        String iterateQuery = "UNWIND $batch AS row RETURN row";
        String updateQuery = """
                WITH row
                MERGE (c:Chunk {id: row.chunkId})
                MERGE (h:Entity {name: row.head})
                MERGE (t:Entity {name: row.tail})
                MERGE (h)-[r:REL_TYPE]->(t)
                SET r.type = row.relation,
                    r.chunkIds = CASE
                        WHEN r.chunkIds IS NULL THEN row.chunkId
                        WHEN NOT r.chunkIds CONTAINS row.chunkId THEN r.chunkIds + ',' + row.chunkId
                        ELSE r.chunkIds
                    END
                MERGE (h)-[:MENTIONED_IN]->(c)
                MERGE (t)-[:MENTIONED_IN]->(c)
                """;
        String call = "CALL apoc.periodic.iterate($iterate, $update, {batchSize:500, parallel:false, params: {batch: $batch}})";

        neo4jClient.query(call)
                .bindAll(Map.of("iterate", iterateQuery, "update", updateQuery, "batch", batch))
                .run();
    }

    // ==================== 图查询与搜索 ====================

    public CommunitySearchResult getCommunitySummariesAndChunks(List<String> entities,
                                                                int maxCommunities,
                                                                int maxChunksPerComm) {
        String cypher = """
                MATCH (e:Entity)
                WHERE e.name IN $entities
                OPTIONAL MATCH (e)-[:BELONGS_TO]->(c:Community)
                WITH e, c
                WHERE c IS NOT NULL
                WITH c, count(DISTINCT e) AS matchCount
                ORDER BY matchCount DESC
                LIMIT $maxCommunities
                OPTIONAL MATCH (c)<-[:BELONGS_TO]-()-[:MENTIONED_IN]->(chk:Chunk)
                WITH
                    coalesce(c.summary, '') AS summary,
                    matchCount,
                    collect(DISTINCT chk.id)[..$maxChunksPerComm] AS chunkIds,
                    'community' AS type
                RETURN summary, matchCount, chunkIds, type
                
                UNION ALL
                
                MATCH (e:Entity)
                WHERE e.name IN $entities
                  AND NOT EXISTS { (e)-[:BELONGS_TO]->(:Community) }
                OPTIONAL MATCH (e)-[:MENTIONED_IN]->(chk:Chunk)
                RETURN
                    '' AS summary,
                    0 AS matchCount,
                    collect(DISTINCT chk.id)[..$maxChunksPerComm] AS chunkIds,
                    'entity' AS type
                LIMIT $maxEntities
                """;

        Map<String, Object> params = Map.of(
                "entities", entities,
                "maxCommunities", maxCommunities,
                "maxChunksPerComm", maxChunksPerComm,
                "maxEntities", maxCommunities * 5); // 限制孤立实体返回量

        List<Map<String, Object>> rows = executeCypher(cypher, params);

        CommunitySearchResult result = new CommunitySearchResult();
        for (Map<String, Object> row : rows) {
            boolean isCommunity = "community".equals(row.get("type"));
            String summary = (String) row.get("summary");
            int matchCount = ((Number) row.get("matchCount")).intValue();

            @SuppressWarnings("unchecked")
            List<String> chunkIds = (List<String>) row.get("chunkIds");

            if (isCommunity && summary != null && !summary.isBlank()) {
                result.addCommunitySummary(summary, matchCount);
            }
            if (chunkIds != null && !chunkIds.isEmpty()) {
                result.addAllChunkIds(chunkIds);
            }
        }
        return result;
    }

    public List<Map.Entry<String, Integer>> buildTemporaryCommunitySummaries(List<String> entities) {
        if (entities == null || entities.isEmpty()) {
            return Collections.emptyList();
        }

        String cypher = """
                MATCH (e:Entity)-[r]->(n:Entity)
                WHERE e.name IN $entities
                RETURN e.name AS entity,
                       apoc.coll.toSet(collect(DISTINCT type(r))) AS relations,
                       collect(DISTINCT n.name)[..5] AS neighbors
                """;

        List<Map<String, Object>> rows = executeCypher(cypher, Map.of("entities", entities));

        return rows.stream().map(row -> {
            String entity = (String) row.get("entity");
            @SuppressWarnings("unchecked")
            List<String> relations = Objects.requireNonNullElse((List<String>) row.get("relations"), List.of());
            @SuppressWarnings("unchecked")
            List<String> neighbors = (List<String>) row.get("neighbors");

            // 对关系排序，提升缓存命中率
            List<String> sortedRels = relations.stream().sorted().toList();
            String cacheKey = entity + "|" + String.join(",", sortedRels);
            String cached = neighborSummaryCache.getIfPresent(cacheKey);
            if (cached != null) {
                return Map.entry(cached, 1);
            }

            List<String> allEntities = new ArrayList<>();
            allEntities.add(entity);
            if (neighbors != null) {
                allEntities.addAll(neighbors);
            }
            String summary = buildSummaryFromEntities(allEntities, sortedRels);
            neighborSummaryCache.put(cacheKey, summary);
            return Map.entry(summary, 1);
        }).collect(Collectors.toList());
    }

    public Map<String, Integer> getUserMessageFileChunkId(String userMessage) {
        List<String> userMessageTokenize = ikAnalyzerTokenize.tokenize(userMessage);
        return getChunkMatchCountMap(userMessageTokenize);
    }

    public Map<String, Integer> getChunkMatchCountMap(List<String> entityNames) {
        if (entityNames == null || entityNames.isEmpty()) {
            return Collections.emptyMap();
        }

        String cypher = """
                MATCH (e:Entity)-[:MENTIONED_IN]->(c:Chunk)
                WHERE e.name IN $names
                WITH c.id AS chunkId, count(DISTINCT e) AS matchCount
                RETURN chunkId, matchCount
                ORDER BY matchCount DESC
                """;

        Map<String, Integer> result = new LinkedHashMap<>();
        executeCypher(cypher, Map.of("names", new ArrayList<>(entityNames)))
                .forEach(record -> {
                    String chunkId = record.get("chunkId").toString();
                    int count = ((Number) record.get("matchCount")).intValue();
                    result.put(chunkId, count);
                });
        return result;
    }

    // ==================== 社区检测（GDS） ====================

    public long runLeidenCommunityDetection() {
        try {
            safeProjectGraph();

            String leiden = """
                    CALL gds.leiden.write('kg', {
                        writeProperty: 'community_0',
                        relationshipTypes: ['MENTIONED_IN'],
                        consecutiveIds: true,
                        randomSeed: 42
                    }) YIELD communityCount, modularity
                    RETURN communityCount, modularity
                    """;
            Map<String, Object> result = neo4jClient.query(leiden).fetch().one().orElse(Map.of());
            long communityCount = ((Number) result.getOrDefault("communityCount", 0L)).longValue();
            log.info("Leiden 完成，社区数：{}，模块度：{}", communityCount, result.get("modularity"));
            return communityCount;
        } catch (Exception e) {
            log.error("Leiden 失败", e);
            return -1;
        } finally {
            dropGraphProjection();
        }
    }

    private void safeProjectGraph() {
        try {
            neo4jClient.query("CALL gds.graph.exists('kg') YIELD exists").fetch().one();
        } catch (Exception e) {
            log.error("safeProjectGraph 失败");
        }
        try {
            neo4jClient.query("CALL gds.graph.drop('kg')").run();
        } catch (Exception ignored) {
        }
        neo4jClient.query("CALL gds.graph.project('kg', 'Entity', {MENTIONED_IN: {orientation: 'UNDIRECTED'}})").run();
    }

    private void dropGraphProjection() {
        try {
            neo4jClient.query("CALL gds.graph.drop('kg')").run();
        } catch (Exception ignored) {
        }
    }

    public void buildCommunityNodes() {
        String iterateQuery = """
                MATCH (e:Entity)-[r]->(t:Entity)
                WHERE e.community_0 IS NOT NULL
                WITH e.community_0 AS cid,
                     collect(DISTINCT e.name)[..5] AS topEntities,
                     collect(DISTINCT type(r))[..3] AS topRelations
                RETURN cid, topEntities, topRelations,
                       '本社区核心实体包括：' + apoc.text.join(topEntities, '、') +
                       CASE WHEN size(topRelations) > 0
                            THEN '，涉及关系：' + apoc.text.join(topRelations, '、') + '。'
                            ELSE '。' END AS summary
                """;

        String updateQuery = """
                MERGE (c:Community {id: toString(cid)})
                SET c.summary = summary, c.level = 0
                WITH c, cid
                MATCH (e:Entity {community_0: toInteger(cid)})
                MERGE (e)-[:BELONGS_TO]->(c)
                """;

        Map<String, Object> params = Map.of("batchSize", 5000, "parallel", false);
        String call = "CALL apoc.periodic.iterate($iterate, $update, $params)";

        neo4jClient.query(call)
                .bindAll(Map.of("iterate", iterateQuery, "update", updateQuery, "params", params))
                .run();

        log.info("Community 节点构建完成（APOC 分批）");
    }

    // ==================== 删除与清理 ====================

    public void deleteChunkRelations(Set<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            log.debug("deleteChunkRelations 被调用但 chunkIds 为空，跳过清理");
            return;
        }

        List<String> delIdList = new ArrayList<>(chunkIds);
        log.info("开始删除文档关系，涉及 {} 个 chunk: {}", delIdList.size(), delIdList);

        // 步骤1：删除不再包含任何文档的 REL_TYPE 关系
        log.info("步骤1/4：清理 REL_TYPE 关系中的待删文档引用");
        String iterateRel = """
                MATCH ()-[r:REL_TYPE]->()
                WHERE r.chunkIds IS NOT NULL AND ANY(id IN $delIds WHERE id IN split(r.chunkIds, ','))
                WITH r, [x IN split(r.chunkIds, ',') WHERE NOT x IN $delIds] AS remaining
                WHERE size(remaining) = 0
                RETURN r
                """;
        String deleteEmptyRel = "DELETE r";
        neo4jClient.query(
                        "CALL apoc.periodic.iterate($iterate, $delete, {batchSize:1000, parallel:false, params: {delIds: $delIds}})")
                .bindAll(Map.of("iterate", iterateRel, "delete", deleteEmptyRel, "delIds", delIdList))
                .run();
        log.info("步骤1完成：已删除仅包含待删文档的 REL_TYPE 关系");

        // 步骤1b：更新仍包含其他文档的关系
        log.info("步骤1b：更新混合文档的 REL_TYPE 关系");
        String iterateUpdateRel = """
                MATCH ()-[r:REL_TYPE]->()
                WHERE r.chunkIds IS NOT NULL AND ANY(id IN $delIds WHERE id IN split(r.chunkIds, ','))
                WITH r, [x IN split(r.chunkIds, ',') WHERE NOT x IN $delIds] AS remaining
                WHERE size(remaining) > 0
                RETURN r, apoc.text.join(remaining, ',') AS newIds
                """;
        String updateRel = "SET r.chunkIds = newIds";
        neo4jClient.query(
                        "CALL apoc.periodic.iterate($iterate, $update, {batchSize:1000, parallel:false, params: {delIds: $delIds}})")
                .bindAll(Map.of("iterate", iterateUpdateRel, "update", updateRel, "delIds", delIdList))
                .run();
        log.info("步骤1b完成：已更新混合文档的 REL_TYPE 关系");

        // 步骤2：删除 Chunk 节点
        log.info("步骤2/4：删除 Chunk 节点及 MENTIONED_IN 关系");
        String iterateChunks = "MATCH (c:Chunk) WHERE c.id IN $delIds RETURN c";
        String deleteChunks = "DETACH DELETE c";
        neo4jClient.query(
                        "CALL apoc.periodic.iterate($iterate, $delete, {batchSize:1000, parallel:false, params: {delIds: $delIds}})")
                .bindAll(Map.of("iterate", iterateChunks, "delete", deleteChunks, "delIds", delIdList))
                .run();
        log.info("步骤2完成：Chunk 节点已删除");

        // 步骤3：删除孤立实体
        log.info("步骤3/4：清理孤立实体");
        removeOrphanEntities();
        log.info("步骤3完成：孤立实体清理完毕");

        // 步骤4：删除空社区
        log.info("步骤4/4：清理空社区");
        removeOrphanCommunities();
        log.info("步骤4完成：空社区清理完毕");

        log.info("文档关系删除全部完成，涉及的 chunk 数量: {}", delIdList.size());
    }

    public void removeOrphanEntities() {
        String iterateOrphan = """
                MATCH (e:Entity)
                WHERE NOT EXISTS { (e)-[:MENTIONED_IN]->(:Chunk) }
                RETURN e
                """;
        String deleteOrphan = "DETACH DELETE e";
        neo4jClient.query("CALL apoc.periodic.iterate($iterate, $delete, {batchSize:5000, parallel:false})")
                .bindAll(Map.of("iterate", iterateOrphan, "delete", deleteOrphan))
                .run();
    }

    public void removeOrphanCommunities() {
        String iterateOrphanComm = """
                MATCH (c:Community)
                WHERE NOT EXISTS { (c)<-[:BELONGS_TO]-() }
                RETURN c
                """;
        String deleteOrphanComm = "DELETE c";
        neo4jClient.query("CALL apoc.periodic.iterate($iterate, $delete, {batchSize:5000, parallel:false})")
                .bindAll(Map.of("iterate", iterateOrphanComm, "delete", deleteOrphanComm))
                .run();
    }

    // ==================== 工具方法 ====================

    private List<Map<String, Object>> executeCypher(String cypher, Map<String, Object> params) {
        return new ArrayList<>(neo4jClient.query(cypher).bindAll(params).fetch().all());
    }

    private String buildSummaryFromEntities(List<String> entities, List<String> relations) {
        String entityPart = String.join("、", entities);
        String relationPart = (relations == null || relations.isEmpty()) ? "" : "，涉及关系：" + String.join("、", relations);
        return "本社区核心实体包括：" + entityPart + relationPart + "。";
    }

    private String esc(String s) {
        return s.replaceAll("[^a-zA-Z0-9_]", "_");
    }
}