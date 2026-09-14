#!/usr/bin/env python3
"""Retrieval-path load test driver for POST /benchmark/retrieval.

- Persistent HTTP connections (one per worker) + JSON POST
- Rotates a fixed query set so L1/L2 caches exercise realistically
- Ramps concurrency and reports p50/p95/p99, RPS, error rate per stage

Usage:
  python scripts/load_test_retrieval.py --token-file /tmp/xy_token.txt \
      --stages 100,500,1000 --requests-per-worker 20
"""
import argparse
import http.client
import json
import random
import statistics
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor

QUERIES = [
    "XYAI 后端 Kafka 改造一共设计了哪几个 Topic？各自用途是什么？",
    "Contextual Retrieval 是什么？为什么说它是性价比最高的检索质量优化？",
    "为什么要用父子切片（parent-child chunking）？解决什么问题？",
    "RAG 系统的多层缓存应该怎么设计？每层缓存什么？",
    "RAG 检索为什么要把 ACL 权限过滤下推到 Milvus 查询层？",
    "CRAG 纠错环（检索质量门控）的工作流程是什么？",
    "Kafka 消费者如何保证消息幂等，防止重复消费导致数据重复入库？",
    "RAG 评估常用的四个指标是什么？分别衡量什么？",
    "trace-log 链路追踪的写入链路是怎样的？",
    "为什么语义缓存必须带 userId 权限维度？",
]


class Worker:
    def __init__(self, host, port, token, results, stop_after):
        self.host, self.port, self.token = host, port, token
        self.results = results
        self.stop_after = stop_after
        self.conn = None

    def _connect(self):
        if self.conn is None:
            self.conn = http.client.HTTPConnection(self.host, self.port, timeout=30)
        return self.conn

    def one(self):
        q = random.choice(QUERIES)
        body = json.dumps({"query": q}, ensure_ascii=False)
        t0 = time.perf_counter()
        try:
            conn = self._connect()
            conn.request("POST", "/benchmark/retrieval", body=body.encode("utf-8"), headers={
                "Content-Type": "application/json",
                "Authorization": "Bearer " + self.token,
            })
            resp = conn.getresponse()
            data = resp.read()
            elapsed = time.perf_counter() - t0
            # Result 包装：HTTP 200 但 body code!=200 视为业务失败
            ok = resp.status == 200 and (b'"code":200' in data or b'"code":0' in data)
            if not ok:
                self.results["errors"] += 1
                if resp.status in (401, 403):
                    self.results["auth_fail"] += 1
                if not self.results.get("last_err") and b'"code"' in data:
                    self.results["last_err"] = data[:160].decode("utf-8", "ignore")
            else:
                self.results["ok"] += 1
            self.results["lat"].append(elapsed)
            return ok
        except Exception as e:
            self.results["errors"] += 1
            self.results["lat"].append(time.perf_counter() - t0)
            self.conn = None  # 连接失效，重建
            if not self.results.get("last_err"):
                self.results["last_err"] = repr(e)[:200]
            return False

    def run(self):
        while self.results["ok"] + self.results["errors"] < self.stop_after:
            self.one()


def percentile(sorted_lat, p):
    if not sorted_lat:
        return 0.0
    idx = min(len(sorted_lat) - 1, int(round(p / 100.0 * (len(sorted_lat) - 1))))
    return sorted_lat[idx]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="localhost")
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument("--token-file", default="/tmp/xy_token.txt")
    ap.add_argument("--stages", default="100,500,1000",
                    help="并发阶梯，逗号分隔")
    ap.add_argument("--per-stage", type=int, default=500,
                    help="每阶段总请求数")
    args = ap.parse_args()

    token = open(args.token_file).read().strip()
    stages = [int(x) for x in args.stages.split(",")]

    # 预热：单并发 20 请求，让缓存建立
    print("== 预热 20 请求（建立 L1/L2 缓存） ==")
    results = {"ok": 0, "errors": 0, "auth_fail": 0, "lat": [], "last_err": None}
    with ThreadPoolExecutor(max_workers=5) as ex:
        workers = [Worker(args.host, args.port, token, results, 20) for _ in range(5)]
        list(ex.map(lambda w: w.run(), workers))
    print(f"预热完成 ok={results['ok']} err={results['errors']}")
    if results["auth_fail"]:
        print("!! 认证失败，请刷新 token", file=sys.stderr)
        return 2

    print(f"{'并发':>6} {'总请求':>8} {'成功':>8} {'错误':>6} {'RPS':>8} {'p50(ms)':>9} {'p95(ms)':>9} {'p99(ms)':>9}")
    for conc in stages:
        results = {"ok": 0, "errors": 0, "auth_fail": 0, "lat": [], "last_err": None}
        stop_after = args.per_stage
        t0 = time.perf_counter()
        with ThreadPoolExecutor(max_workers=conc) as ex:
            workers = [Worker(args.host, args.port, token, results, stop_after) for _ in range(conc)]
            # 公共目标：全组合计 stop_after 个请求，由计数器控制
            list(ex.map(lambda w: w.run(), workers))
        wall = time.perf_counter() - t0
        lat = sorted(results["lat"])
        rps = len(lat) / wall if wall > 0 else 0
        print(f"{conc:>6} {len(lat):>8} {results['ok']:>8} {results['errors']:>6} "
              f"{rps:>8.1f} {percentile(lat,50)*1000:>9.0f} {percentile(lat,95)*1000:>9.0f} "
              f"{percentile(lat,99)*1000:>9.0f}")
        if results["last_err"]:
            print(f"      last_err: {results['last_err']}")
        time.sleep(3)  # 阶段间冷却
    return 0


if __name__ == "__main__":
    sys.exit(main())
