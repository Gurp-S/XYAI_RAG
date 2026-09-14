package com.XYai.myai.rag.memory;

import com.XYai.myai.commonUtils.IK.IKAnalyzerTokenize;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用户长期记忆服务（MemGPT / Generative Agents 思想的确定性实现）。
 *
 * <p>与「会话摘要记忆」分层：会话摘要只覆盖当前会话且依赖 LLM；
 * 本服务维护<b>跨会话的长期事实层</b>，规则式抽取 + 三因子加权召回，全程不依赖 LLM：</p>
 *
 * <ul>
 *   <li><b>记忆形成（remember）</b>：从用户消息中用触发句式抽取事实
 *       （记住/我叫/我是/我在…工作/我负责/我喜欢/我的…是…等），去重合并，带重要度；
 *       对应 Generative Agents (Park et al., 2023) 的 memory stream 与
 *       MemGPT (Packer et al., 2023) 的 core memory 之外的外部记忆层。</li>
 *   <li><b>记忆召回（recall）</b>：score = 0.45·relevance + 0.35·importance + 0.20·recency，
 *       与 Generative Agents 的 recency×importance×relevance 检索评分同构；
 *       显式记忆句式（"我叫什么/上次说的"）自动提升重要度权重。</li>
 *   <li><b>上限治理</b>：单用户最多 N 条，超出后按低价值（低重要度×低访问）淘汰，
 *       防止无限膨胀。</li>
 * </ul>
 *
 * <p>存储：MySQL 表 xy_memory_fact（启动幂等建表）。进程内 Caffeine 按 userId 缓存，
 * 写入即失效，读多写少。</p>
 */
@Slf4j
@Service
public class LongTermMemoryService {

    private static final int MAX_FACTS_PER_USER_DEFAULT = 300;
    private static final int RECALL_TOP_K_DEFAULT = 5;
    /** 判定"相关"的最低召回分（0~1） */
    private static final double RELEVANT_SCORE = 0.22;

    /** 显式记忆召回信号：命中时重要度权重提升，用于"我上次说的/我叫什么"类问题 */
    private static final Pattern EXPLICIT_RECALL_MARKER = Pattern.compile(
            "记住|记得|上次(?:说|提|告诉)|之前(?:说|提|告诉)|我叫什么|我(?:的)?名字|我是谁|我(?:的)?公司|我(?:的)?项目|我(?:的)?团队");

    // ==================== 抽取规则（复合句宽松匹配，逗号/句号截断） ====================

    private record Rule(Pattern pattern, int importance, String kind) {}

    private static final List<Rule> RULES = List.of(
            new Rule(Pattern.compile("(?:请记住|帮我记住|记住)[:：]?\\s*(.{1,120}?)\\s*(?:[。！？!?；;]|$)"), 7, "explicit"),
            new Rule(Pattern.compile("我叫(.{1,40}?)\\s*(?:[，,。；;]|$)"), 6, "name"),
            new Rule(Pattern.compile("我任职于(.{1,40}?)\\s*(?:[，,。；;]|$)"), 6, "employer"),
            // "我在星辰科技做AI平台后端开发" / "我在XX工作"：抓到句读前整段
            new Rule(Pattern.compile("我在(?!想问|想查|想看|想了解|需要|这|那)(.{1,50}?)\\s*(?:[，,。；;]|$)"), 6, "work"),
            new Rule(Pattern.compile("我是(?!想问|想查|想看|想了解|需要|在找|来问|来咨询|新手|小白|帮)(?:一名?|一个)?(.{1,30}?)\\s*(?:[，,。；;]|$)"), 6, "identity"),
            new Rule(Pattern.compile("我(?:负责|从事)(.{1,40}?)\\s*(?:相关)?(?:工作)?\\s*(?:[，,。；;]|$)"), 6, "work"),
            new Rule(Pattern.compile("我(?:比较)?(?:喜欢|偏爱|偏好)(.{1,50}?)\\s*(?:[，,。；;]|$)"), 5, "preference"),
            new Rule(Pattern.compile("我(?:比较)?(?:讨厌|不喜欢|反感)(.{1,50}?)\\s*(?:[，,。；;]|$)"), 4, "aversion"),
            new Rule(Pattern.compile("我的(公司|团队|部门|项目|领导|导师|老板)(?:的名字)?(?:叫|是|为)(.{1,50}?)\\s*(?:[，,。；;]|$)"), 6, "belonging"),
            new Rule(Pattern.compile("我的(?!公司|团队|部门|项目|领导|导师|老板|问题|意思|想法|疑问|需求)(.{1,20}?)(?:是|叫|为)(.{1,60}?)\\s*(?:[，,。；;]|$)"), 5, "fact"),
            new Rule(Pattern.compile("(?:目标是|计划是|下一步要|接下来要|想在今年|打算)(.{1,60}?)\\s*(?:[，,。；;]|$)"), 5, "plan")
    );

    private record Fact(long id, String fact, int importance, int hitCount, LocalDateTime updatedAt) {}

    @Resource
    private JdbcTemplate jdbcTemplate;
    @Resource
    private IKAnalyzerTokenize ikAnalyzerTokenize;

    @Value("${rag.memory.longterm.max-per-user:300}")
    private int maxFactsPerUser;

    private final com.github.benmanes.caffeine.cache.Cache<Long, List<Fact>> cache =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                    .maximumSize(4096)
                    .expireAfterWrite(Duration.ofMinutes(5))
                    .build();

    @PostConstruct
    public void initSchema() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS xy_memory_fact (
                    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
                    user_id             BIGINT        NOT NULL COMMENT '用户ID',
                    fact                VARCHAR(300)  NOT NULL COMMENT '记忆事实文本',
                    importance          TINYINT       DEFAULT 5 COMMENT '重要度1-7',
                    hit_count           INT           DEFAULT 0 COMMENT '被召回命中次数',
                    last_hit_at         DATETIME      NULL,
                    source_conversation_id BIGINT      NULL,
                    create_time         DATETIME DEFAULT CURRENT_TIMESTAMP,
                    update_time         DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    UNIQUE KEY uk_user_fact (user_id, fact(150))
                ) COMMENT='用户长期记忆事实表'""");
        log.info("长期记忆表 xy_memory_fact 初始化完成");
    }

    // ==================== 记忆形成 ====================

    /**
     * 从用户消息抽取并存储长期记忆。无匹配则静默跳过。
     */
    public void remember(Long userId, String userMessage, Long conversationId) {
        if (userId == null || userMessage == null || userMessage.isBlank()) return;
        int stored = 0;
        for (Rule rule : RULES) {
            Matcher m = rule.pattern().matcher(userMessage);
            if (!m.find()) continue;
            String fact = composeFact(rule, m);
            if (fact == null) continue;
            if (fact.length() > 200) fact = fact.substring(0, 200);
            String normalized = normalize(fact);
            if (normalized.length() < 2 || isNoise(normalized)) continue;
            upsert(userId, normalized, rule.importance(), conversationId);
            stored++;
        }
        if (stored > 0) {
            cache.invalidate(userId);
            log.info("长期记忆新增/更新: userId={}, 条数={}", userId, stored);
        }
    }

    /** 按规则把捕获组拼成完整事实文本；无法拼出返回 null */
    private String composeFact(Rule rule, Matcher m) {
        try {
            String a = m.groupCount() >= 1 ? m.group(1) : null;
            String b = m.groupCount() >= 2 ? m.group(2) : null;
            if (rule.kind().equals("explicit")) {
                if (a == null || a.isBlank() || a.length() < 3) return null;
                return a;
            }
            if (rule.kind().equals("name")) return a == null || a.isBlank() ? null : a;
            // 归属/事实/计划句式若捕获到"什么/怎么/多少/哪"等疑问片段，说明是提问而非陈述
            if ((rule.kind().equals("belonging") || rule.kind().equals("fact")
                    || rule.kind().equals("plan") || rule.kind().equals("work"))
                    && (isQuestionFragment(a) || (b != null && isQuestionFragment(b)))) {
                return null;
            }
            if (rule.kind().equals("belonging")) {
                if (a == null || b == null) return null;
                return "我的" + a + "：" + b;
            }
            if (rule.kind().equals("fact")) {
                if (a == null || b == null) return null;
                return "我的" + a + "是" + b;
            }
            if (rule.kind().equals("work") || rule.kind().equals("employer")
                    || rule.kind().equals("identity") || rule.kind().equals("preference")
                    || rule.kind().equals("aversion") || rule.kind().equals("plan")) {
                return a == null || a.isBlank() ? null : a;
            }
            return a;
        } catch (Exception e) {
            log.debug("记忆事实拼接失败: {}", e.getMessage());
            return null;
        }
    }

    private boolean isNoise(String normalized) {
        return Set.of("这个", "那个", "一下", "好的", "可以", "没问题", "谢谢", "谢谢了").contains(normalized);
    }

    private void upsert(Long userId, String fact, int importance, Long conversationId) {
        List<Long> exists = jdbcTemplate.queryForList(
                "SELECT id FROM xy_memory_fact WHERE user_id=? AND fact=? LIMIT 1",
                Long.class, userId, fact);
        if (!exists.isEmpty()) {
            jdbcTemplate.update(
                    "UPDATE xy_memory_fact SET importance=GREATEST(importance,?), update_time=NOW() WHERE id=?",
                    importance, exists.get(0));
            return;
        }
        // 近似去重：与近期已有事实 bigram 相似度 ≥0.8 视为同义，仅提升重要度不重复入库
        Long similarId = findSimilar(userId, fact);
        if (similarId != null) {
            jdbcTemplate.update(
                    "UPDATE xy_memory_fact SET importance=GREATEST(importance,?), update_time=NOW() WHERE id=?",
                    importance, similarId);
            return;
        }
        jdbcTemplate.update(
                "INSERT INTO xy_memory_fact(user_id,fact,importance,source_conversation_id) VALUES(?,?,?,?)",
                userId, fact, importance, conversationId);
        evictIfOverflow(userId);
    }

    /** 与最近 50 条记忆做字符 bigram Jaccard 相似度，≥0.8 视为同一事实 */
    private Long findSimilar(Long userId, String fact) {
        try {
            List<Map<String, Object>> recent = jdbcTemplate.queryForList(
                    "SELECT id, fact FROM xy_memory_fact WHERE user_id=? ORDER BY id DESC LIMIT 50",
                    userId);
            Set<String> target = bigrams(fact);
            if (target.isEmpty()) return null;
            Long best = null;
            double bestSim = 0;
            for (Map<String, Object> row : recent) {
                String f = String.valueOf(row.get("fact"));
                Set<String> other = bigrams(f);
                if (other.isEmpty()) continue;
                int inter = 0;
                for (String g : target) {
                    if (other.contains(g)) inter++;
                }
                double sim = (double) inter / Math.max(1, target.size() + other.size() - inter);
                if (sim > bestSim) {
                    bestSim = sim;
                    best = ((Number) row.get("id")).longValue();
                }
            }
            return bestSim >= 0.8 ? best : null;
        } catch (Exception e) {
            log.debug("近似去重查询失败: {}", e.getMessage());
            return null;
        }
    }

    private static Set<String> bigrams(String s) {
        String t = s.replaceAll("[\\s：:，,。.；;！!？?、（）()「」\"'“”‘’]", "").toLowerCase(Locale.ROOT);
        Set<String> out = new HashSet<>();
        if (t.length() < 2) {
            if (!t.isEmpty()) out.add(t);
            return out;
        }
        for (int i = 0; i < t.length() - 1; i++) {
            out.add(t.substring(i, i + 2));
        }
        return out;
    }

    /** 捕获片段是否更像提问（问词结尾/含疑问词），是则不当作事实入库 */
    private static boolean isQuestionFragment(String s) {
        if (s == null || s.isBlank()) return true;
        String t = s.strip().toLowerCase(Locale.ROOT);
        if (t.endsWith("吗") || t.endsWith("呢") || t.endsWith("？") || t.endsWith("?")) return true;
        if (t.contains("什么") || t.contains("怎么") || t.contains("如何")
                || t.contains("多少") || t.contains("哪") || t.contains("几")
                || t.contains("是不是") || t.contains("有没有") || t.contains("吗")) return true;
        return false;
    }

    private void evictIfOverflow(Long userId) {
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM xy_memory_fact WHERE user_id=?", Integer.class, userId);
        if (cnt == null || cnt <= maxFactsPerUser) return;
        // 淘汰低价值：score = importance * (1 + log2(hit_count+1))，保留最高 N
        jdbcTemplate.update("""
                DELETE FROM xy_memory_fact WHERE user_id=? AND id NOT IN (
                  SELECT id FROM (
                    SELECT id FROM xy_memory_fact
                    WHERE user_id=?
                    ORDER BY importance * (1 + LOG2(hit_count + 1)) DESC
                    LIMIT ?
                  ) t
                )""", userId, userId, maxFactsPerUser);
        log.warn("长期记忆超上限，已淘汰低价值条目: userId={}", userId);
    }

    // ==================== 记忆召回 ====================

    /**
     * 按查询召回与当前问题相关的长期记忆。
     *
     * @return 命中的记忆文本（每行一条，前缀无序号格式）与相关性标记
     */
    public RecallResult recall(Long userId, String query, int topK) {
        if (userId == null) return RecallResult.empty();
        List<Fact> facts;
        try {
            facts = cache.get(userId, k -> jdbcTemplate.query(
                    "SELECT id, fact, importance, hit_count, update_time FROM xy_memory_fact WHERE user_id=?",
                    (rs, i) -> {
                        LocalDateTime upd = rs.getTimestamp("update_time") == null ? null
                                : rs.getTimestamp("update_time").toLocalDateTime();
                        return new Fact(rs.getLong("id"), rs.getString("fact"),
                                rs.getInt("importance"), rs.getInt("hit_count"), upd);
                    }, userId));
        } catch (Exception e) {
            log.warn("长期记忆加载失败: {}", e.getMessage());
            return RecallResult.empty();
        }
        if (facts.isEmpty()) return RecallResult.empty();

        Set<String> queryTokens = tokenize(query);
        boolean explicitMarker = queryTokens.isEmpty()
                || EXPLICIT_RECALL_MARKER.matcher(query).find();

        List<Scored> scored = new ArrayList<>();
        for (Fact f : facts) {
            Set<String> factTokens = tokenize(f.fact());
            int hit = 0;
            for (String qt : queryTokens) {
                if (factTokens.contains(qt)) hit++;
            }
            double relevance = queryTokens.isEmpty() ? 0.0 : (double) hit / queryTokens.size();
            double importanceN = f.importance() / 7.0;
            if (explicitMarker) importanceN = Math.min(1.0, importanceN + 0.25);
            double recencyN = f.updatedAt() == null ? 0.5
                    : Math.exp(-Duration.between(f.updatedAt(), LocalDateTime.now()).toDays() / 14.0);
            double score = 0.45 * relevance + 0.35 * importanceN + 0.20 * recencyN;
            if (score > 0) {
                scored.add(new Scored(f, score));
            }
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());

        List<Scored> top = scored.size() > topK ? scored.subList(0, topK) : scored;
        if (top.isEmpty()) return RecallResult.empty();

        boolean relevant = top.getFirst().score() >= RELEVANT_SCORE;
        // 命中计数与最近命中时间（异步低频更新，防写放大）
        List<Long> ids = top.stream().map(s -> s.fact().id()).toList();
        String idList = String.join(",", ids.stream().map(String::valueOf).toList());
        try {
            jdbcTemplate.update("UPDATE xy_memory_fact SET hit_count=hit_count+1, last_hit_at=NOW() WHERE id IN (" + idList + ")");
        } catch (Exception e) {
            log.debug("更新记忆命中计数失败: {}", e.getMessage());
        }
        cache.invalidate(userId);

        StringBuilder sb = new StringBuilder();
        for (Scored s : top) {
            if (!sb.isEmpty()) sb.append('\n');
            sb.append(s.fact().fact());
        }
        return new RecallResult(sb.toString(), relevant, top.getFirst().score());
    }

    private Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) return Set.of();
        try {
            List<String> t = ikAnalyzerTokenize.tokenize(text);
            return t == null ? Set.of() : new HashSet<>(t);
        } catch (Exception e) {
            return Set.of();
        }
    }

    private static String normalize(String s) {
        return s.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    public int count(Long userId) {
        if (userId == null) return 0;
        Integer c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM xy_memory_fact WHERE user_id=?", Integer.class, userId);
        return c == null ? 0 : c;
    }

    private record Scored(Fact fact, double score) {}

    /** 召回结果：text=命中的记忆文本；relevant=是否有足够相关的记忆支撑个人化作答 */
    public record RecallResult(String text, boolean relevant, double topScore) {
        public static RecallResult empty() {
            return new RecallResult("", false, 0.0);
        }
    }
}
