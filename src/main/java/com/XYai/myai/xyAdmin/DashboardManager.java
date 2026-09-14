package com.XYai.myai.xyAdmin;

import com.XYai.myai.security.annotation.AdminOnly;
import com.XYai.myai.rag.aop.annotation.RateLimit;

import com.XYai.myai.commonUtils.redis.RedisKeyConfig;
import com.XYai.myai.mapper.*;
import com.XYai.myai.rag.chat.pojo.ModelRouterProperties;
import com.XYai.myai.user.pojo.User;
import com.XYai.myai.xyAdmin.mapper.TokenRecordMapper;
import com.XYai.myai.xyAdmin.pojo.TokenRecord;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 管理后台 - 系统总览 Dashboard。
 * 提供实时统计指标、对话趋势图表、文件使用趋势图表。
 * <p>
 * 接口分类：
 * [统计概览] GET /stats      - 系统核心指标聚合
 * [趋势图表] GET /chart      - 每日对话次数与Token消耗
 * [趋势图表] GET /fileChart  - 每日文件使用次数
 */
@Slf4j
@AdminOnly
@RestController
@RequestMapping("/xyAdmin/dashboard")
public class DashboardManager {

    // 最小时间（早于任何业务时间）
    private Map<String, Object> cachedStats;
    private long lastStatsFetch = 0;
    private static final long STATS_CACHE_TTL_MS = 5000;

    @Resource
    private StringRedisTemplate redis;
    @Resource
    private NodeRecordMapper nodeRecordMapper;
    @Resource
    private TokenRecordMapper tokenRecordMapper;
    @Resource
    private FileRecordMapper fileRecordMapper;
    @Resource
    private UserEvaluateMapper userEvaluateMapper;
    @Resource
    private UserMapper userMapper;
    @Resource
    private ModelRouterProperties modelRouterProperties;
    @Resource
    private SystemEvaluateMapper systemEvaluateMapper;

    /**
     * [统计概览] 聚合关键指标
     */
    @GetMapping("/stats")
    @RateLimit(limit = 60, rateName = "admin_dashboard_stats", windowMs = 60_000)
    public Map<String, Object> getStats() {
        // 5s 缓存避免重复聚合查询
        long now = System.currentTimeMillis();
        if (cachedStats != null && (now - lastStatsFetch) < STATS_CACHE_TTL_MS) {
            return cachedStats;
        }

        Map<String, Object> stats = new LinkedHashMap<>();

        // 用户总数
        stats.put("userCount", safeCount(userMapper, "userCount"));
        // 追踪链路数
        stats.put("traceCount", safeCount(nodeRecordMapper, "traceCount"));
        // Token 总消耗（SUM total_tokens 而非行数）
        long tokenCount = 0L;
        try {
            QueryWrapper<TokenRecord> wrapper = new QueryWrapper<>();
            wrapper.select("COALESCE(SUM(total_tokens), 0) as total");
            List<Map<String, Object>> sumResult = tokenRecordMapper.selectMaps(wrapper);
            if (sumResult != null && !sumResult.isEmpty()) {
                Object val = sumResult.getFirst().get("total");
                if (val instanceof Number) {
                    tokenCount = ((Number) val).longValue();
                }
            }
        } catch (Exception e) {
            log.warn("tokenCount 查询失败", e);
        }
        stats.put("tokenCount", tokenCount);
        // 评估总数（用户评估 + 系统评估）
        long evalCount = 0;
        try {
            evalCount = userEvaluateMapper.selectCount(null);
        } catch (Exception e) {
            log.warn("userEvaluateCount 查询失败", e);
        }
        try {
            evalCount += systemEvaluateMapper.selectCount(null);
        } catch (Exception e) {
            log.warn("systemEvaluateCount 查询失败", e);
        }
        stats.put("evaluateCount", evalCount);

        // 平均链路耗时（SQL 聚合，避免全表加载）
        double avgCost = 0;
        try {
            Long avgCostTime = nodeRecordMapper.selectAvgCostTime();
            avgCost = avgCostTime != null ? avgCostTime : 0;
        } catch (Exception e) {
            log.warn("avgCostTime 查询失败", e);
        }
        stats.put("avgCostTime", Math.round(avgCost));

        // Milvus 集合数
        try {
            stats.put("milvusCollections", countKeysByPattern("xyai:collection:files:*"));
        } catch (Exception e) {
            log.warn("milvusCollections 获取失败", e);
            stats.put("milvusCollections", 0);
        }

        // 文件总数
        stats.put("fileCount", countKeysByPattern("xyai:file:hash:*"));

        // 在线用户数
        long onlineCount = 0L;
        try {
            Long countObj = userMapper.selectCount(new QueryWrapper<User>().eq("status", true));
            if (countObj != null) {
                onlineCount = countObj;
            }
        } catch (Exception e) {
            log.warn("onlineUserCount 获取失败", e);
        }
        stats.put("onlineUserCount", onlineCount);

        // 默认对话模型
        String defaultModel = "—";
        try {
            if (modelRouterProperties != null && modelRouterProperties.getFeatureModels() != null) {
                defaultModel = modelRouterProperties.getFeatureModels().getOrDefault("chat_default", "—");
            }
        } catch (Exception e) {
            log.warn("defaultModel 获取失败", e);
        }
        stats.put("defaultModel", defaultModel);

        // 最新公告
        String announcement = "";
        try {
            List<String> anns = redis.opsForList().range("xy:announcements", -1, -1);
            if (anns != null && !anns.isEmpty()) {
                announcement = anns.getFirst();
            }
        } catch (Exception e) {
            log.warn("latestAnnouncement 获取失败", e);
        }
        stats.put("latestAnnouncement", announcement);

        cachedStats = stats;
        lastStatsFetch = now;
        return stats;
    }

    /**
     * 每日对话次数与 Token 消耗趋势
     */
    @GetMapping("/chart")
    public List<Map<String, Object>> getChartData(@RequestParam(defaultValue = "7") int days) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            LocalDate today = LocalDate.now();
            LocalDateTime startDate = today.minusDays(days).atStartOfDay();

            // 1. 查询数据库中最近 N 天的汇总数据
            QueryWrapper<TokenRecord> wrapper = new QueryWrapper<>();
            wrapper.select("DATE(created_at) as date",
                            "COUNT(*) as count",
                            "COALESCE(SUM(total_tokens), 0) as tokens")
                    .ge("created_at", startDate)
                    .groupBy("DATE(created_at)")
                    .orderByAsc("DATE(created_at)");
            List<Map<String, Object>> dbResult = tokenRecordMapper.selectMaps(wrapper);

            // 2. 将数据库结果转换为 Map<LocalDate, Map<String, Object>>
            Map<LocalDate, Map<String, Object>> dateMap = new HashMap<>();
            for (Map<String, Object> row : dbResult) {
                Object dateObj = row.get("date");
                LocalDate date = null;
                if (dateObj instanceof Date) {
                    date = ((java.sql.Date) dateObj).toLocalDate();
                } else if (dateObj instanceof LocalDate) {
                    date = (LocalDate) dateObj;
                } else if (dateObj instanceof String) {
                    date = LocalDate.parse((String) dateObj);
                }
                if (date != null) {
                    dateMap.put(date, row);
                }
            }

            // 3. 生成完整日期序列，填充数据
            for (int i = 0; i < days; i++) {
                LocalDate date = today.minusDays(days - 1 - i); // 从最旧到最新
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("date", date.toString());          // "YYYY-MM-DD"

                Map<String, Object> dbRow = dateMap.get(date);
                if (dbRow != null) {
                    // 确保 count 和 tokens 为数值类型
                    Object countVal = dbRow.getOrDefault("count", 0L);
                    Object tokensVal = dbRow.getOrDefault("tokens", 0L);
                    long count = countVal instanceof Number ? ((Number) countVal).longValue() : 0L;
                    long tokens = tokensVal instanceof Number ? ((Number) tokensVal).longValue() : 0L;
                    item.put("count", count);
                    item.put("tokens", tokens);
                } else {
                    item.put("count", 0L);
                    item.put("tokens", 0L);
                }
                result.add(item);
            }
        } catch (Exception e) {
            log.warn("token chart 查询失败，可能表未初始化", e);
        }
        return result;
    }

    /**
     * 每日文件使用次数趋势（从 Redis 读取，自动过期 30 天）
     * 若 Redis 数据缺失则自动从 MySQL 同步。
     */
    @GetMapping("/fileChart")
    public List<Map<String, Object>> getFileChartData(@RequestParam(defaultValue = "7") int days) {
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = days; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            String countStr = redis.opsForValue().get(RedisKeyConfig.dailyFileUseCountKey(date));
            long count = countStr != null ? Long.parseLong(countStr) : 0L;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", date.toString());
            item.put("fileCount", count);
            result.add(item);
        }
        return result;
    }

    /**
     * 安全执行 selectCount，异常时返回 0 并记录日志。
     */
    private long safeCount(BaseMapper<?> mapper, String metricName) {
        try {
            return mapper.selectCount(null);
        } catch (Exception e) {
            log.warn("{} 统计失败", metricName, e);
            return 0L;
        }
    }

    public void addFileUseCount() {
        LocalDate today = LocalDate.now();
        String key = RedisKeyConfig.dailyFileUseCountKey(today);
        redis.opsForValue().increment(key);
        redis.expire(key, 30, TimeUnit.DAYS);
    }

    /**
     * 用 SCAN 替代 KEYS 命令统计匹配的 key 数量，避免 Redis 阻塞。
     */
    private int countKeysByPattern(String pattern) {
        try {
            Set<String> keys = redis.execute((RedisCallback<Set<String>>) conn -> {
                Set<String> matched = new HashSet<>();
                try (Cursor<byte[]> cursor = conn.keyCommands().scan(
                        ScanOptions.scanOptions().match(pattern).count(500).build())) {
                    while (cursor.hasNext()) {
                        matched.add(new String(cursor.next(), StandardCharsets.UTF_8));
                    }
                }
                return matched;
            });
            return keys != null ? keys.size() : 0;
        } catch (Exception e) {
            log.warn("SCAN failed for pattern {}", pattern, e);
            return 0;
        }
    }
}