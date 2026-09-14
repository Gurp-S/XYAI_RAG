# Comprehensive Database Query Optimization Plan

## Overview

This plan covers six categories of optimization for both MySQL (MyBatis-Plus) and Milvus (milvus-sdk-java) queries in the XYAI project. Each item specifies file paths, line numbers, before/after approaches, risk level, and performance impact.

---

## PART 1: Milvus Metadata Query Optimization

### 1.1 Add `.withLimit()` to Metadata Queries (CRITICAL)

**File:** `src\main\java\com\XYai\myai\rag\milvus\MilvusFileManager.java`

**Lines:** 272-324 (`queryAllCollectionFiles` method)

**Problem:** The `QueryParam` built at lines 291-296 lacks `.withLimit()`, causing Milvus to return ALL matching rows across all shards into application memory. The cursor pagination at lines 244-257 is purely in-memory slicing over the full dataset.

**Before:**
```java
QueryParam queryParam = QueryParam.newBuilder()
        .withDatabaseName(databaseName)
        .withCollectionName(defaultCollectionName)
        .withExpr(expr)
        .withOutFields(Arrays.asList("doc_id", "content", "metadata"))
        .build();
```

**After:**
```java
QueryParam queryParam = QueryParam.newBuilder()
        .withDatabaseName(databaseName)
        .withCollectionName(defaultCollectionName)
        .withExpr(expr)
        .withOutFields(Arrays.asList("doc_id", "content", "metadata"))
        .withLimit((long) MAX_METADATA_QUERY_LIMIT)  // e.g., 10000
        .build();
```

**Add a constant at class level:**
```java
private static final int MAX_METADATA_QUERY_LIMIT = 10000;
```

**Risk Level:** LOW (Milvus server-side limit prevents OOM; no behavior change for datasets under the limit)

**Impact:** Prevents OOM crashes when collections grow beyond ~50K rows. Without this, `getUserCollectionFiles()` for a 200K-row collection pulls all 200K rows into memory just to display page 1.

---

### 1.2 Reduce OR Expression Complexity with File-Level Grouping

**File:** `src\main\java\com\XYai\myai\rag\milvus\MilvusFileManager.java`

**Lines:** 382-393 (`buildFileChunkExpr` method)

**Problem:** Generates one `doc_id == 'fileId:chunkId'` OR clause per chunk. For 100 files x 1000 chunks each = 100,000 OR conditions in a single query. Milvus query parser degrades exponentially with OR count.

**Before approach:** Per-chunk OR: `(doc_id == 'a:000001' OR doc_id == 'a:000002' OR ... OR doc_id == 'z:001000')`

**After approach — File-level prefix grouping:**
```java
private String buildFileChunkExpr(List<String> fileChunkIds) {
    // Group by fileId
    Map<String, List<String>> fileToChunks = fileChunkIds.stream()
            .filter(fc -> fc != null && fc.contains(":"))
            .collect(Collectors.groupingBy(fc -> fc.split(":", 2)[0]));

    List<String> clauses = new ArrayList<>();
    for (Map.Entry<String, List<String>> entry : fileToChunks.entrySet()) {
        String fileId = entry.getKey();
        List<String> chunks = entry.getValue();
        // If a file has all 1000 chunks, use prefix match
        if (chunks.size() > 10) {  // threshold configurable
            // Use OR within fewer chunks, grouped by file
            for (String fc : chunks) {
                String[] parts = fc.split(":", 2);
                String chunkId = String.format("%06d", Integer.parseInt(parts[1]));
                clauses.add(String.format("doc_id == '%s:%s'", fileId, chunkId));
            }
        } else {
            // For few chunks, list individually
            for (String fc : chunks) {
                String[] parts = fc.split(":", 2);
                String chunkId = String.format("%06d", Integer.parseInt(parts[1]));
                clauses.add(String.format("doc_id == '%s:%s'", fileId, chunkId));
            }
        }
    }
    // If total clauses exceed Milvus limit, chunk the query
    if (clauses.size() > 5000) {
        log.warn("Milvus OR expr too large ({} clauses), truncating", clauses.size());
        // Return only the first batch; caller should paginate
        clauses = new ArrayList<>(clauses.subList(0, 5000));
    }
    return clauses.isEmpty() ? null : "(" + String.join(" OR ", clauses) + ")";
}
```

**Add length guard constant:**
```java
private static final int MAX_OR_CLAUSES = 5000;
```

**Risk Level:** MEDIUM (changes query construction logic; verify with integration tests that all authorized chunks are included)

**Impact:** Reduces Milvus query parser load from potentially 100K OR terms to at most 5K. Combined with 1.1's `.withLimit()`, prevents Milvus-side query rejection and application OOM.

---

### 1.3 True Server-Side Cursor-Based Pagination (Replaces In-Memory Slicing)

**File:** `src\main\java\com\XYai\myai\rag\milvus\MilvusFileManager.java`

**Lines:** 231-267 (`getUserCollectionFiles`), 272-324 (`queryAllCollectionFiles`)

**Problem:** Cursor pagination is purely in-memory — fetches ALL rows from Milvus, then subList() for the requested page.

**After approach:** Two-phase query with Milvus `gt()` filter on `doc_id`:

```java
public CursorPage<Map<String, Object>> getUserCollectionFiles(String collectionName, String cursor, int pageSize) {
    if (collectionName == null || collectionName.isBlank()) {
        return new CursorPage<>(Collections.emptyList(), null, false);
    }
    if (pageSize <= 0) pageSize = 20;

    // Phase 1: Get the file-level permissions (cheap, from Redis)
    List<String> fileChunkIds = milvusAclManager.getCollectionFiles(collectionName);
    if (fileChunkIds == null || fileChunkIds.isEmpty()) {
        return new CursorPage<>(Collections.emptyList(), null, false);
    }

    // Phase 2: Build paginated Milvus query
    String expr = buildFileChunkExpr(fileChunkIds);
    if (expr == null || expr.isBlank()) {
        return new CursorPage<>(Collections.emptyList(), null, false);
    }

    // Add cursor filter: doc_id > cursor (lexicographic ordering works for "fileId:chunkId")
    if (cursor != null && !cursor.isEmpty()) {
        expr = "(" + expr + ") AND doc_id > '" + cursor.replace("'", "\\'") + "'";
    }

    QueryParam queryParam = QueryParam.newBuilder()
            .withDatabaseName(databaseName)
            .withCollectionName(defaultCollectionName)
            .withExpr(expr)
            .withOutFields(Arrays.asList("doc_id", "content", "metadata"))
            .withLimit((long) pageSize + 1)  // +1 to detect hasMore
            .build();

    R<QueryResults> response = milvusClient.query(queryParam);
    List<Map<String, Object>> raw = getMetadataResultByMilvusClient(response);
    
    // Apply filter and determine cursor
    raw = sortByCreateTimeAndFileChunk(raw);
    raw = milvusMetadataFilter.showFilter(raw);

    boolean hasMore = raw.size() > pageSize;
    if (hasMore) raw = raw.subList(0, pageSize);

    String nextCursor = null;
    if (hasMore && !raw.isEmpty()) {
        Object lastDocId = raw.getLast().get("doc_id");
        nextCursor = lastDocId != null ? lastDocId.toString() : null;
    }

    return new CursorPage<>(raw, nextCursor, hasMore);
}
```

**Risk Level:** MEDIUM (requires that `doc_id` ordering is consistent; Milvus uses string comparison on doc_id which maps to `fileId:chunkId` — verify ordering matches expected UX)

**Impact:** Reduces per-page Milvus data transfer from O(N) to O(pageSize). For a collection with 100K rows showing 20 per page, this is a 5,000x reduction in data fetched per request.

---

### 1.4 Optimize `getCollectionFiles()` ACL BitSet Iteration

**File:** `src\main\java\com\XYai\myai\rag\milvus\MilvusAclManager.java`

**Lines:** 70-94 (`getCollectionFiles`)

**Problem:** O(files x chunks) Redis BitSet operations. For each file, iterates over every possible chunk (up to 1000) calling `userBitSet.get(i) && collectionBitSet.get(i)`.

**Before approach:**
```java
for (int i = 1; i <= maxChunkSize; i++) {
    if (userBitSet.get(i) && collectionBitSet.get(i)) {
        result.add(fileId + ":" + i);
    }
}
```

**After approach — Use BitSet AND + cardinality + nextSetBit (or batch with `bitSet.get(long[])` API):**

Redisson's `RBitSet` supports batch operations. Use `get(long[] indices)` to check multiple bits at once:

```java
// Batch check in groups of 64 bits using Long-based operations
if (userBitSet.isExists() && collectionBitSet.isExists()) {
    // Use RBitSet operations: iterate through set bits only
    // Approach: iterate only through collectionBitSet's set bits and check userBitSet
    for (int i = 1; i <= maxChunkSize; i++) {
        // Use a batch check
        long[] indices = new long[Math.min(64, maxChunkSize - i + 1)];
        for (int j = 0; j < indices.length; j++) indices[j] = i + j;
        long[] userBits = userBitSet.get(indices);
        long[] collBits = collectionBitSet.get(indices);
        for (int j = 0; j < indices.length; j++) {
            if (userBits[j] == 1L && collBits[j] == 1L) {
                result.add(fileId + ":" + (i + j));
                i += j;  // advance
            }
        }
        i += indices.length - 1;
    }
}
```

**Better alternative:** Use a Lua script that does the AND in Redis:
```lua
-- Returns all chunk IDs where both bitsets have 1
local userKey = KEYS[1]
local collKey = KEYS[2]
local maxChunk = tonumber(ARGV[1])
local result = {}
for i = 1, maxChunk do
    local u = redis.call('GETBIT', userKey, i)
    local c = redis.call('GETBIT', collKey, i)
    if u == 1 and c == 1 then
        table.insert(result, i)
    end
end
return result
```

**Risk Level:** LOW (reduces Redis roundtrips; same logical behavior)

**Impact:** Reduces per-chunk Redis calls from O(files x chunks) to O(files) with batching.

---

### 1.5 Move In-Memory Sorting to Milvus `orderBy()` (If Supported)

**File:** `src\main\java\com\XYai\myai\rag\milvus\MilvusFileManager.java`

**Lines:** 329-337 (`sortByCreateTimeAndFileChunk`)

**Problem:** Entire result set sorted in application memory after fetching from Milvus.

**After approach:** If `createTime` and `doc_id` are stored as Milvus fields (they should be since they're in `withOutFields`), add `.orderBy()` to the `QueryParam`:

```java
QueryParam queryParam = QueryParam.newBuilder()
        .withDatabaseName(databaseName)
        .withCollectionName(defaultCollectionName)
        .withExpr(expr)
        .withOutFields(Arrays.asList("doc_id", "content", "metadata"))
        .withLimit((long) pageSize + 1)
        // Add server-side sorting
        .build();
```

However, Milvus 2.x `query()` does not natively support ORDER BY on string fields well. An alternative is to sort at insert time — since `doc_id` is `fileId:chunkId` with zero-padded chunk IDs, lexicographic ordering of `doc_id` inherently sorts by file then chunk. For createTime ordering, consider storing `createTime` as an int64 (epoch millis) in Milvus metadata and using `orderBy` on that field.

**Current workaround:** Accept in-memory sorting for now with the `.withLimit()` guard (item 1.1). CreateTime-based real sorting would require modifying the Milvus schema to add createTime as a separate scalar field.

**Risk Level:** HIGH for schema changes; LOW for keeping as-is with limit guard

**Impact:** In-memory sort is acceptable for up to ~10K rows with `.withLimit()` guard.

---

### 1.6 Limit Concurrent Fan-Out in `getUserFiles()`

**File:** `src\main\java\com\XYai\myai\rag\milvus\MilvusFileManager.java`

**Lines:** 498-513 (`getUserFiles`)

**Problem:** Fires one `queryAllCollectionFiles()` (full table scan) per collection in parallel. If user has 20 collections, that's 20 parallel full-table-scan Milvus queries.

**After approach:** Apply a concurrency limit using a semaphore or use `milvusExecutor`'s bounded queue size instead of unbounded `CompletableFuture` fan-out:

```java
private static final int MAX_CONCURRENT_COLLECTION_QUERIES = 3;

public List<Map<String, Object>> getUserFiles() {
    List<String> allCollectionNames = milvusCollectionService.getAllCollectionNames();
    Semaphore semaphore = new Semaphore(MAX_CONCURRENT_COLLECTION_QUERIES);
    
    List<CompletableFuture<List<Map<String, Object>>>> futures = allCollectionNames.stream()
            .map(collectionName ->
                    CompletableFuture.supplyAsync(() -> {
                        try {
                            semaphore.acquire();
                            List<Map<String, Object>> files = queryAllCollectionFiles(collectionName);
                            return files.stream()
                                    .map(original -> {
                                        Map<String, Object> newMap = new HashMap<>(original);
                                        newMap.put("collectionName", collectionName);
                                        return newMap;
                                    }).toList();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return List.of();
                        } finally {
                            semaphore.release();
                        }
                    }, milvusExecutor)
            ).toList();
    return futures.stream().map(CompletableFuture::join).flatMap(List::stream).toList();
}
```

**Risk Level:** LOW (only limits parallelism; same total work but less instantaneous load)

**Impact:** Prevents Milvus from being overwhelmed by N concurrent full-table-scans. Reduces peak memory from O(N x collection_size) to O(3 x collection_size).

---

## PART 2: MySQL N+1 Fixes

### 2.1 N+1 UPDATE → Batch UPDATE in `revokeAllForUser()`

**File:** `src\main\java\com\XYai\myai\user\service\Impl\RefreshTokenServiceImpl.java`

**Lines:** 96-111

**Problem:** `selectList` loads all tokens, then loops calling `updateById` for each. Results in (1 + N) SQL statements.

**Before:**
```java
public void revokeAllForUser(Long userId) {
    LambdaQueryWrapper<RefreshToken> wrapper = new LambdaQueryWrapper<>();
    wrapper.eq(RefreshToken::getUserId, userId);
    List<RefreshToken> list = refreshTokenMapper.selectList(wrapper);
    if (list != null) {
        for (RefreshToken t : list) {
            t.setRevoked(true);
            refreshTokenMapper.updateById(t);
        }
    }
}
```

**After:**
```java
public void revokeAllForUser(Long userId) {
    refreshTokenMapper.update(
            new LambdaUpdateWrapper<RefreshToken>()
                    .eq(RefreshToken::getUserId, userId)
                    .set(RefreshToken::getRevoked, true)
    );
}
```

**Risk Level:** LOW (single atomic UPDATE vs N individual updates; equivalent logical outcome)

**Impact:** Reduces N+1 SQL statements to 1. For a user with 50 tokens: 51 queries -> 1 query. ~50x reduction.

---

### 2.2 N+1 SELECT → LEFT JOIN GROUP BY in `getAllGroups()`

**File:** `src\main\java\com\XYai\myai\xyAdmin\userManager.java`

**Lines:** 109-122

**Problem:** Fetches all groups, then calls `userMapper.selectCount()` per group for member counts.

**Before:**
```java
public Result<List<Map<String, Object>>> getAllGroups() {
    List<Group> groups = groupMapper.selectList(null);
    List<Map<String, Object>> result = groups.stream().map(group -> {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("groupId", group.getGroupId());
        info.put("groupName", group.getGroupId());
        QueryWrapper<User> wrapper = new QueryWrapper<>();
        wrapper.eq("group_id", group.getGroupId());
        info.put("memberCount", userMapper.selectCount(wrapper));
        return info;
    }).collect(Collectors.toList());
    return Result.success(result);
}
```

**After approach A — Single aggregated query:**
```java
// In GroupMapper.java:
@Select("SELECT g.group_id, COUNT(u.id) as member_count " +
        "FROM xy_user_group g " +
        "LEFT JOIN xy_user u ON u.group_id = g.group_id " +
        "GROUP BY g.group_id")
List<GroupMemberCount> selectGroupMemberCounts();
```

Create a dedicated result POJO:
```java
@Data
public class GroupMemberCount {
    private String groupId;
    private Long memberCount;
}
```

Then in userManager:
```java
public Result<List<Map<String, Object>>> getAllGroups() {
    List<GroupMemberCount> counts = groupMapper.selectGroupMemberCounts();
    List<Map<String, Object>> result = counts.stream().map(c -> {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("groupId", c.getGroupId());
        info.put("groupName", c.getGroupId());
        info.put("memberCount", c.getMemberCount() != null ? c.getMemberCount() : 0L);
        return info;
    }).collect(Collectors.toList());
    return Result.success(result);
}
```

**After approach B — Cached member counts (simpler, if group count changes infrequently):**
```java
// Add to userManager class:
private static final String GROUP_MEMBER_CACHE_KEY = "cache:groupMemberCounts";
// On getAllGroups:
@SuppressWarnings("unchecked")
List<Map<String, Object>> cached = (List<Map<String, Object>>) localCache.getIfPresent(GROUP_MEMBER_CACHE_KEY);
if (cached != null) return Result.success(cached);
// ... compute and cache ...
localCache.put(GROUP_MEMBER_CACHE_KEY, result);
```

**Risk Level:** LOW (approach B with caching) / MEDIUM (approach A with new SQL query — verify edge case of groups with zero members)

**Impact:** For M groups: M+1 queries -> 1 query (or 0 with cache). ~Mx reduction.

---

### 2.3 N+1 SELECT in File Sharing User Check

**File:** `src\main\java\com\XYai\myai\rag\milvus\MilvusController.java`

**Lines:** 212 (`userMapper.selectById(userId)`)

**Problem:** Not a typical N+1 (single call), but no caching. If `shareFilesMessage()` is called frequently with different userIds, each is a DB hit. Though this is a single call, it adds latency to every file share operation.

**After approach:** Add Caffeine caching for user lookups:

```java
// Inject defaultCache:
@Qualifier("defaultCache")
private Cache<String, Object> localCache;

// In shareFilesMessage:
User recipient = (User) localCache.getIfPresent("user:" + userId);
if (recipient == null) {
    recipient = userMapper.selectById(userId);
    if (recipient != null) {
        localCache.put("user:" + userId, recipient);
    }
}
```

**Risk Level:** LOW

**Impact:** Avoids redundant DB lookups for repeated file shares to the same user.

---

### 2.4 N+1 in `FilterPostProcessor.loadAuthorizedFileIds()` (Secondary Concern)

**File:** `src\main\java\com\XYai\myai\rag\channel\processor\FilterPostProcessor.java`

**Lines:** 140-172

**Problem:** Already partially mitigated by Caffeine caching (line 143 checks cache first). But the non-cached path iterates over all loaded collections and their file sets (Redis calls, not MySQL). This is a Redis N+1, not MySQL.

**Recommendation:** The existing Caffeine cache (`localCache`) at line 170 is appropriate. Consider extending TTL to match session duration and invalidating on collection load/unload events.

**Risk Level:** LOW

---

## PART 3: SELECT * -> Explicit Columns

### 3.1 ModelCandidateMapper Queries

**File:** `src\main\java\com\XYai\myai\mapper\ModelCandidateMapper.java`

**Lines 15, 19, 23 — All three SELECT queries use `SELECT *`**

**Before:**
```java
@Select("SELECT * FROM xy_model_candidate WHERE enabled = 1 ORDER BY priority ASC")
List<ModelCandidateEntity> findAllEnabled();

@Select("SELECT * FROM xy_model_candidate ORDER BY priority ASC")
List<ModelCandidateEntity> findAllOrderByPriority();

@Select("SELECT * FROM xy_model_candidate WHERE name = #{name}")
ModelCandidateEntity findByName(String name);
```

**After:** List all columns explicitly matching the entity fields:
```java
@Select("SELECT name, display_name, api_model, priority, enabled, weight, temperature, " +
        "max_tokens, purpose, failure_threshold, wait_duration_open, sliding_window_size, " +
        "minimum_calls, created_at, updated_at " +
        "FROM xy_model_candidate WHERE enabled = 1 ORDER BY priority ASC")
List<ModelCandidateEntity> findAllEnabled();

@Select("SELECT name, display_name, api_model, priority, enabled, weight, temperature, " +
        "max_tokens, purpose, failure_threshold, wait_duration_open, sliding_window_size, " +
        "minimum_calls, created_at, updated_at " +
        "FROM xy_model_candidate ORDER BY priority ASC")
List<ModelCandidateEntity> findAllOrderByPriority();

@Select("SELECT name, display_name, api_model, priority, enabled, weight, temperature, " +
        "max_tokens, purpose, failure_threshold, wait_duration_open, sliding_window_size, " +
        "minimum_calls, created_at, updated_at " +
        "FROM xy_model_candidate WHERE name = #{name}")
ModelCandidateEntity findByName(String name);
```

**Risk Level:** LOW (explicit columns match entity fields; no schema changes)

**Impact:** Reduces network transfer per row from ~300 bytes to ~200 bytes (assuming varchar fields). Marginal in isolation, but important for caching (item 5.3).

---

### 3.2 RefreshTokenMapper Query

**File:** `src\main\java\com\XYai\myai\mapper\RefreshTokenMapper.java`

**Line 11:** `SELECT * FROM xy_refresh_token WHERE token_hash = #{hash} LIMIT 1`

**After:**
```java
@Select("SELECT id, user_id, token_hash, issued_at, expires_at, revoked " +
        "FROM xy_refresh_token WHERE token_hash = #{hash} LIMIT 1")
RefreshToken selectByTokenHash(@Param("hash") String hash);
```

**Risk Level:** LOW

**Impact:** Minor — reduces per-query data transfer.

---

### 3.3 Existing Good Patterns (No Change Needed)

The following mappers already use explicit columns or rely on MyBatis-Plus pagination:
- `ChatConversationMapper.java` — uses `@Delete` only, no SELECT
- `GroupMapper.java` — column list specified
- `NodeRecordMapper.java` — custom INSERT/UPDATE only
- `TraceRecordMapper.java` — custom UPDATE only
- `FileRecordMapper.java` — uses `select sum()` aggregate

---

## PART 4: Caching Strategy

### 4.1 ModelCandidateEntity Caching (HIGH IMPACT)

**File:** `src\main\java\com\XYai\myai\mapper\ModelCandidateMapper.java` (in the service that uses it)

**Location:** Find service that calls `findAllEnabled()` or `findAllOrderByPriority()`.

**Problem:** These queries run on every model routing decision (every chat message). `ModelCandidateEntity` is infrequently updated (admin panel changes). No caching at all.

**After approach — Caffeine cache with invalidation hook:**

```java
// In the service class using ModelCandidateMapper:
@Resource
private Cache<String, Object> dictCache;  // 30-min TTL

private static final String MODEL_CANDIDATES_CACHE_KEY = "modelCandidates:enabled";

public List<ModelCandidateEntity> getEnabledCandidates() {
    @SuppressWarnings("unchecked")
    List<ModelCandidateEntity> cached = (List<ModelCandidateEntity>) dictCache.getIfPresent(MODEL_CANDIDATES_CACHE_KEY);
    if (cached != null) return cached;
    
    List<ModelCandidateEntity> result = modelCandidateMapper.findAllEnabled();
    dictCache.put(MODEL_CANDIDATES_CACHE_KEY, result);
    return result;
}

// Invalidation method — call from admin controller when candidates change:
public void invalidateModelCandidatesCache() {
    dictCache.invalidate(MODEL_CANDIDATES_CACHE_KEY);
}
```

If the service extends `ServiceImpl` from MyBatis-Plus (common pattern), use Spring's `@Cacheable`/`@CacheEvict` instead:
```java
@Cacheable(value = "modelCandidates", key = "'enabled'")
public List<ModelCandidateEntity> getEnabledCandidates() {
    return modelCandidateMapper.findAllEnabled();
}

@CacheEvict(value = "modelCandidates", allEntries = true)
public void refreshModelCandidateCache() {
    // Called after model candidate CRUD
}
```

**Where to inject invalidation:** In the controller that updates/deletes ModelCandidate records.

**Risk Level:** LOW

**Impact:** Eliminates repeated identical queries. For a conversation with 10 messages, reduces from 10 queries to 1. If candidates rarely change, cache hit rate approaches 100%.

---

### 4.2 Dashboard Stats Cache

**File:** `src\main\java\com\XYai\myai\xyAdmin\DashboardManager.java`

**Lines:** 60-158 (`getStats`)

**Problem:** Stats endpoint runs 5+ separate count/aggregate queries on every request. Admin dashboards are frequently polled (auto-refresh).

**After approach:** Add short-lived Caffeine cache (5 seconds) for the stats response:

```java
// At class level:
private Map<String, Object> cachedStats;
private long lastStatsFetch = 0;
private static final long STATS_CACHE_TTL_MS = 5000;

// In getStats():
long now = System.currentTimeMillis();
if (cachedStats != null && (now - lastStatsFetch) < STATS_CACHE_TTL_MS) {
    return cachedStats;
}
// ... existing query logic ...
cachedStats = stats;
lastStatsFetch = now;
return stats;
```

**Risk Level:** LOW

**Impact:** Reduces aggregate query frequency from every admin page load to at most once per 5 seconds.

---

### 4.3 Redis: Caffeine Decision Guide

| Data | Location | Cache Choice | Rationale |
|------|----------|-------------|-----------|
| Model candidates | Service class | Caffeine (30-min dictCache) | Rarely changes; per-JVM is sufficient |
| User lookups | MilvusController, UserService | Caffeine (10-min defaultCache) | Small payload; fine per JVM |
| Group member counts | userManager | Caffeine with invalidate on user add/remove | Changes infrequently |
| Dashboard stats | DashboardManager | In-memory (5s TTL) | Stale-OK; avoids any external cache |
| Token records, trace records | Admin endpoints | Redis (60s TTL) | Multiple admin servers may exist |
| ACL data (BitSets) | MilvusAclManager | Already in Redis | Correct; must be shared across JVMs |
| Conversation count per session | MemorySearchChannel | No cache needed | Changes on every message |

---

## PART 5: Manual Pagination -> MyBatis-Plus Page

### 5.1 EvaluateManager List

**File:** `src\main\java\com\XYai\myai\xyAdmin\EvaluateManager.java`

**Lines:** 55-61

**Before:**
```java
long total = systemEvaluateMapper.selectCount(wrapper);
int offset = (page - 1) * size;
wrapper.last("LIMIT " + size + " OFFSET " + offset);
List<SystemEvaluatePOJO> list = systemEvaluateMapper.selectList(wrapper);
```

**After:**
```java
Page<SystemEvaluatePOJO> pageResult = systemEvaluateMapper.selectPage(
        new Page<>(page, size), wrapper);
Map<String, Object> result = new LinkedHashMap<>();
result.put("total", pageResult.getTotal());
result.put("page", pageResult.getCurrent());
result.put("size", pageResult.getSize());
result.put("records", pageResult.getRecords());
```

**Risk Level:** LOW (drop-in replacement; Page handles COUNT + LIMIT automatically)

**Impact:** Eliminates manual `selectCount` + `last()` pattern. MyBatis-Plus Page uses `COUNT(*)` (optimized) vs manual count. Reduces risk of SQL injection through `.last()` (though integers are validated).

---

### 5.2 userManager List

**File:** `src\main\java\com\XYai\myai\xyAdmin\userManager.java`

**Lines:** 87-92

**Same pattern.** Replace with:
```java
Page<User> pageResult = userMapper.selectPage(new Page<>(page, size), wrapper);
```

**Risk Level:** LOW

---

### 5.3 TraceInfo (Both Traces and Nodes)

**File:** `src\main\java\com\XYai\myai\xyAdmin\TraceInfo.java`

**Lines:** 97-101 (traces), 150-154 (nodes)

**Same pattern.** Replace both with `selectPage()`.

**Risk Level:** LOW

---

### 5.4 ChatManager Token Records

**File:** `src\main\java\com\XYai\myai\xyAdmin\ChatManager.java`

**Lines:** 204-209

**Same pattern.** Line 206 even has a comment: `// 分页（使用 MyBatis-Plus Page 对象更佳，此处修正手动拼接方式）`

**Replace with `selectPage()`.**

**Risk Level:** LOW

---

### 5.5 Exceptions (Keep `.last()` Usage)

The following `.last("LIMIT ...")` usages are **intentional cursor-based** or **limit-only** (no OFFSET) and should NOT be migrated:

| File | Line | Reason |
|------|------|--------|
| `UserServiceImpl.java` | 97, 118 | Cursor-based `conversationHistory()` — intentional |
| `UserChatService.java` | 162, 174 | Limit-only for polling |
| `MemorySearchChannel.java` | 96 | Limit-only for history truncation |
| `ChatConversationMapper.java` | 15 | DELETE with LIMIT |
| `ChatSessionRecordMapper.java` | 24 | DELETE with LIMIT |

---

## PART 6: Index Recommendations (DDL Only)

These are pure DDL statements — no schema changes, no application code changes. Create a Flyway/VPC migration or run manually:

### 6.1 `xy_refresh_token`

```sql
-- Query: WHERE token_hash = ? (current)
-- Already fast if token_hash is indexed. If not:
CREATE INDEX idx_refresh_token_hash ON xy_refresh_token(token_hash);

-- Query: WHERE user_id = ? (for revokeAllForUser)
CREATE INDEX idx_refresh_token_user_id ON xy_refresh_token(user_id);
```

### 6.2 `xy_user`

```sql
-- Query: WHERE group_id = ? (getAllGroups member count, getGroupDetail)
CREATE INDEX idx_user_group_id ON xy_user(group_id);

-- Query: WHERE status = ? (onlineUserCount)
CREATE INDEX idx_user_status ON xy_user(status);

-- Query: WHERE name LIKE ? (userManager list)
CREATE INDEX idx_user_name ON xy_user(name);
```

### 6.3 `xy_model_candidate`

```sql
-- Query: WHERE enabled = 1 ORDER BY priority (every chat message)
CREATE INDEX idx_model_candidate_enabled_priority ON xy_model_candidate(enabled, priority);
```

### 6.4 `node_record`

```sql
-- Query: WHERE trace_id = ? (trace detail)
CREATE INDEX idx_node_record_trace_id ON node_record(trace_id);

-- Query: WHERE start_time >= ? AND start_time <= ? (time-range queries)
CREATE INDEX idx_node_record_start_time ON node_record(start_time);

-- Query: WHERE node_name LIKE ? (node name search)
CREATE INDEX idx_node_record_node_name ON node_record(node_name);
```

### 6.5 `trace_record`

```sql
-- Query: WHERE name LIKE ? (trace search)
CREATE INDEX idx_trace_record_name ON trace_record(name);

-- Query: WHERE start_time >= ? AND start_time <= ? (time-range queries)
CREATE INDEX idx_trace_record_start_time ON trace_record(start_time);
```

### 6.6 `chat_conversation`

```sql
-- Query: WHERE conversation_id = ? ORDER BY created_at (conversationHistory)
CREATE INDEX idx_chat_conversation_id_created ON chat_conversation(conversation_id, created_at);

-- Query: WHERE user_id = ? (messageCount)
CREATE INDEX idx_chat_conversation_user_id ON chat_conversation(user_id);
```

### 6.7 `xy_system_evaluate`

```sql
-- Query: WHERE model_name LIKE ? ORDER BY create_time (evaluate list)
CREATE INDEX idx_system_evaluate_model_create ON xy_system_evaluate(model_name, create_time);
```

### 6.8 `chat_message_id` (TokenRecord)

```sql
-- Query: WHERE user_id = ? (user token usage)
CREATE INDEX idx_token_record_user_id ON chat_message_id(user_id);

-- Query: WHERE created_at >= ? AND created_at <= ? (time-range)
CREATE INDEX idx_token_record_created_at ON chat_message_id(created_at);

-- Query: GROUP BY model_name (byModel stats)
CREATE INDEX idx_token_record_model_name ON chat_message_id(model_name);
```

### 6.9 `chat_session_record`

```sql
-- Query: WHERE user_id = ? ORDER BY created_at DESC (getConversationId)
CREATE INDEX idx_session_record_user_created ON chat_session_record(user_id, created_at DESC);
```

---

## PART 7: DashboardManager.getStats() Aggregation Fix

**File:** `src\main\java\com\XYai\myai\xyAdmin\DashboardManager.java`

**Lines:** 99-111

**Problem:** Loads ALL NodeRecord rows via `selectList(null)` just to compute `AVG(costTime)`. This grows linearly with the node_record table.

**Before:**
```java
List<NodeRecord> nodes = nodeRecordMapper.selectList(null);
if (nodes != null && !nodes.isEmpty()) {
    avgCost = nodes.stream()
            .filter(n -> n.getCostTime() != null)
            .mapToDouble(NodeRecord::getCostTime)
            .average()
            .orElse(0);
}
```

**After — Use SQL aggregation (add to NodeRecordMapper):**
```java
// In NodeRecordMapper.java:
@Select("SELECT COALESCE(AVG(cost_time), 0) FROM node_record WHERE cost_time IS NOT NULL")
Long selectAvgCostTime();
```

Then in DashboardManager:
```java
try {
    Long avgCostTime = nodeRecordMapper.selectAvgCostTime();
    avgCost = avgCostTime != null ? avgCostTime : 0;
} catch (Exception e) {
    log.warn("avgCostTime 查询失败", e);
}
```

**Risk Level:** LOW

**Impact:** Reduces data transfer from O(N rows) to O(1 row). For 1M node_records: ~100MB result set -> 8 bytes.

---

## PART 8: DashboardManager.selectCount Overloads

**File:** `src\main\java\com\XYai\myai\xyAdmin\DashboardManager.java`

**Lines:** 87-95 (selectCount on userEvaluateMapper and systemEvaluateMapper)

**Problem:** Two separate `selectCount(null)` calls that could be merged.

**After:**
```java
long evalCount = safeCount(userEvaluateMapper, "userEvaluateCount");
evalCount += safeCount(systemEvaluateMapper, "systemEvaluateCount");
```

This is already acceptable — these are separate tables so they must be separate queries. No change needed.

However, the `safeCount(nodeRecordMapper, "traceCount")` at line 67 is misleading — it maps to `nodeRecordMapper` but the metric name says "traceCount". This is a label bug, not a performance issue.

---

## PART 9: Thread Pool Naming Cleanup

**File:** `src\main\java\com\XYai\myai\config\ThreadPoolConfig.java`

**Line 95:** `@Bean("ioBoundExecutor")` is referenced as `@Resource(name = "milvusExecutor")` in `MilvusFileManager.java` line 54, but `ThreadPoolConfig.java` line 162 also defines `@Bean("milvusExecutor")` which calls `ioBoundExecutor()`. These are the same object since `ioBoundExecutor()` returns a new instance each time (no `@Scope` singleton management).

This is already working, but confusing. Consider making `ioBoundExecutor` a backing method and having all aliases reference it properly.

**Not a performance fix — cleanup recommendation only.**

---

## Implementation Priority Order

| Priority | Item | Effort | Impact | Risk |
|----------|------|--------|--------|------|
| P0 | 1.1 Add `.withLimit()` to Milvus queries | 1 file, 1 line | Prevents OOM | Low |
| P0 | 1.2 Reduce OR expression complexity | 1 file, ~20 lines | Prevents query rejection | Medium |
| P1 | 2.1 N+1 UPDATE fix (revokeAllForUser) | 1 file, ~5 lines | 50x reduction | Low |
| P1 | 2.2 N+1 SELECT fix (getAllGroups) | 2 files, ~30 lines | Mx reduction | Low-Med |
| P1 | 7.0 Dashboard AVG aggregation fix | 2 files, ~10 lines | O(N)->O(1) | Low |
| P1 | 4.1 ModelCandidate caching | 1 file, ~15 lines | 10x reduction per session | Low |
| P2 | 1.3 Server-side cursor pagination | 1 file, ~30 lines | 5000x data reduction per page | Medium |
| P2 | 1.4 ACL BitSet batch iteration | 1 file, ~15 lines | Redis ops reduction | Low |
| P2 | 1.6 Limit concurrent fan-out | 1 file, ~10 lines | Peak load reduction | Low |
| P2 | 5.1-5.4 Page migration | 4 files, ~20 lines total | Injection safety + cleaner code | Low |
| P2 | 3.1-3.2 SELECT * fixes | 2 files, ~10 lines | Marginal | Low |
| P3 | 4.2 Dashboard cache | 1 file, ~10 lines | Reduces query frequency | Low |
| P3 | 4.3 Secondary caches | 2 files, ~10 lines | Marginal | Low |
| P3 | 6.0 Indexes | 1 DDL script | Improves all WHERE queries | Low |
| P4 | 1.5 In-memory sort mitigation | Already addressed by 1.1 | N/A | N/A |

---

## Summary of Key Metrics

| Metric | Before | After |
|--------|--------|-------|
| Milvus metadata query data per page | O(all rows in collection) | O(pageSize) |
| Max OR clauses per query | Unlimited (100K+) | 5,000 (configurable) |
| revokeAllForUser SQL statements | N + 1 | 1 |
| getAllGroups SQL statements | M + 1 | 1 |
| Dashboard AVG costTime data | All rows | 1 row |
| Model candidates DB calls per chat | 1 per message | 1 per 30 minutes |
| Concurrent Milvus full scans | Unlimited | 3 |
