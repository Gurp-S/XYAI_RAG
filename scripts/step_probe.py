import http.client, json, threading, time, random, sys
token = open(r"C:/Users/48522/AppData/Local/Temp/xy_token.txt").read().strip()
QUERIES = [
    "XYAI 后端 Kafka 改造一共设计了哪几个 Topic？各自用途是什么？",
    "Contextual Retrieval 是什么？为什么说它是性价比最高的检索质量优化？",
    "为什么要用父子切片（parent-child chunking）？解决什么问题？",
    "RAG 系统的多层缓存应该怎么设计？每层缓存什么？",
    "RAG 检索为什么要把 ACL 权限过滤下推到 Milvus 查询层？",
    "CRAG 纠错环（检索质量门控）的工作流程是什么？",
    "Kafka 消费者如何保证消息幂等？",
    "RAG 评估常用的四个指标是什么？",
    "trace-log 链路追踪的写入链路是怎样的？",
    "为什么语义缓存必须带 userId 权限维度？",
]
lat, ret, ent, mem, rw, total = [], [], [], [], [], []
lock = threading.Lock()
stop = int(sys.argv[1]) if len(sys.argv) > 1 else 600
conc = int(sys.argv[2]) if len(sys.argv) > 2 else 200

def worker():
    conn = http.client.HTTPConnection("localhost", 8080, timeout=60)
    while True:
        with lock:
            if len(lat) >= stop:
                break
        q = random.choice(QUERIES)
        t0 = time.perf_counter()
        try:
            conn.request("POST", "/benchmark/retrieval",
                         body=json.dumps({"query": q}).encode(),
                         headers={"Content-Type": "application/json",
                                  "Authorization": "Bearer " + token})
            r = conn.getresponse()
            d = r.read()
            el = time.perf_counter() - t0
            try:
                data = (json.loads(d).get("data")) or {}
            except Exception:
                data = {}
        except Exception:
            el = time.perf_counter() - t0
            data = {}
            conn.close()
        with lock:
            lat.append(el)
            total.append(el * 1000)
            ret.append(data.get("stepRetrievalMs") or 0)
            ent.append(data.get("stepEntityMs") or 0)
            mem.append(data.get("stepMemoryMs") or 0)
            rw.append(data.get("stepRewriteMs") or 0)

ts = [threading.Thread(target=worker) for _ in range(conc)]
t0 = time.perf_counter()
[t.start() for t in ts]
[t.join() for t in ts]
wall = time.perf_counter() - t0

def pct(a, p):
    a = sorted(a)
    return a[min(len(a) - 1, int(p / 100 * (len(a) - 1)))]

print(f"n={len(lat)} conc={conc} RPS={len(lat)/wall:.0f} "
      f"lat_p50={pct(lat,50)*1000:.0f}ms p95={pct(lat,95)*1000:.0f}ms p99={pct(lat,99)*1000:.0f}ms")
print(f"stepRetrieval p50={pct(ret,50):.0f}ms p95={pct(ret,95):.0f}ms | "
      f"entity p50={pct(ent,50):.0f}ms | rewrite p50={pct(rw,50):.0f}ms | memory p50={pct(mem,50):.0f}ms")
