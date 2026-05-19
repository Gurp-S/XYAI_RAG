package com.XYai.myai.rag.graph;

import com.XYai.myai.commonUtils.IK.IKAnalyzerTokenize;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
public class EntityRecognizer {

    @Resource
    private Neo4jClient neo4jClient;
    @Resource
    private IKAnalyzerTokenize ikAnalyzerTokenize;

    private final Cache<String, Boolean> entityCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofHours(1))
            .maximumSize(50_000)
            .build();

    @PostConstruct
    public void loadEntities() {
        refreshEntities();
    }

    public synchronized void refreshEntities() {
        entityCache.invalidateAll();
        List<String> names = getAllEntityNames();
        names.stream()
                .filter(name -> name != null && name.trim().length() > 1)
                .map(String::trim)
                .forEach(name -> entityCache.put(name, Boolean.TRUE));
        log.info("Neo4j 加载实体名完成，有效实体数: {}", entityCache.estimatedSize());
    }

    /**
     * 提取查询中匹配的实体，按长度降序，限制数量
     */
    public List<String> extractEntities(String query, int maxEntities) {
        List<String> tokens = ikAnalyzerTokenize.tokenize(query);
        Set<String> tokenSet = new HashSet<>(tokens);
        log.info("分词结果: {}", tokens);
        Set<String> cachedEntities = entityCache.asMap().keySet();
        List<String> matched = cachedEntities.stream()
                .filter(tokenSet::contains)
                .sorted(Comparator.comparingInt(String::length).reversed())
                .limit(maxEntities)
                .collect(Collectors.toList());
        log.info("匹配到的实体: {}", matched);
        return matched;
    }

    private List<String> getAllEntityNames() {
        String cypher = "MATCH (e:Entity) RETURN e.name";
        return (List<String>) neo4jClient.query(cypher)
                .fetchAs(String.class)
                .all();
    }

    public List<String> extractEntities(String query) {
        return extractEntities(query, 5);
    }
}