#!/usr/bin/env python3
"""CRAG 适配评测：证据已灌库，逐题调 /benchmark/retrieval，统计检索指标。

指标（CRAG 协议的检索侧适配，LLM 生成侧待充值后接入）：
  EvidenceHit@8      检索 Top-8 中是否含该题证据页面（fileId 前缀匹配）
  AnswerCover@8      gold answer（规范化）是否出现在检索文本中
  MRR                证据页面首块排名的倒数均值
"""
import hashlib
import json
import re
import statistics
import subprocess
import sys
import time
import urllib.request
from collections import defaultdict
from pathlib import Path

BASE = Path(__file__).resolve().parent
CORPUS = BASE / "corpus"
TOKEN = open(r"C:/Users/48522/AppData/Local/Temp/xy_token.txt").read().strip()


def norm(s: str) -> str:
    return re.sub(r"[\s\W_]+", "", (s or "").lower())


def answer_tokens(ans: str):
    toks = re.findall(r"[a-z0-9]+|[\u4e00-\u9fff]", norm(ans))
    return set(t for t in toks if len(t) >= 2) or set(t for t in toks if t)


def retrieve(query: str):
    body = json.dumps({"query": query}).encode("utf-8")
    req = urllib.request.Request(
        "http://localhost:8080/benchmark/retrieval", data=body,
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + TOKEN})
    with urllib.request.urlopen(req, timeout=60) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    if data.get("code") != 200:
        raise RuntimeError(str(data)[:200])
    return data["data"]


def main():
    manifest = json.loads((BASE / "eval_manifest.json").read_text(encoding="utf-8"))
    # 题目 → 证据文件 hash 集合
    page_hashes = {}
    for item in manifest:
        hashes = set()
        for page in item["pages"]:
            p = CORPUS / page
            if p.exists():
                hashes.add(hashlib.sha256(p.read_bytes()).hexdigest())
        item["hashes"] = hashes
        page_hashes[item["interaction_id"]] = hashes

    results = []
    for idx, item in enumerate(manifest):
        try:
            data = retrieve(item["query"])
        except Exception as e:
            results.append({**item, "error": str(e)[:120], "hit": 0, "cover": 0, "mrr": 0.0, "latency": 0})
            print(f"[{idx+1}/{len(manifest)}] ERROR {e}")
            continue
        previews = data.get("chunkPreviews") or []
        hashes = item["hashes"]
        hit_rank = 0
        cover = 0
        ans_norm = norm(item["answer"])
        ans_tokens = answer_tokens(item["answer"])
        concat = norm(" ".join((c.get("contentPreview") or "") for c in previews))
        for rank, c in enumerate(previews, start=1):
            cid = (c.get("id") or "")
            prefix = cid.split(":")[0]
            if prefix in hashes and hit_rank == 0:
                hit_rank = rank
            if ans_norm and ans_norm in concat:
                cover = 1
            elif ans_tokens:
                # 宽松：答案词 80% 出现在检索文本
                hits = sum(1 for t in ans_tokens if t in concat)
                if hits / max(1, len(ans_tokens)) >= 0.8:
                    cover = 1
        mrr = 1.0 / hit_rank if hit_rank else 0.0
        latency = data.get("totalMs") or 0
        rec = {k: v for k, v in item.items() if k != "hashes"}
        results.append({**rec, "hit": 1 if hit_rank else 0, "cover": cover,
                        "mrr": round(mrr, 4), "hit_rank": hit_rank, "latency": latency})
        print(f"[{idx+1}/{len(manifest)}] hit={hit_rank or '-'} cover={cover} mrr={mrr:.3f} "
              f"lat={latency}ms :: {item['query'][:36]}")

    (BASE / "crag_eval_results.json").write_text(
        json.dumps(results, ensure_ascii=False, indent=1), encoding="utf-8")

    def agg(rows, label):
        if not rows:
            return
        hit = statistics.mean(r["hit"] for r in rows)
        cover = statistics.mean(r["cover"] for r in rows)
        mrr = statistics.mean(r["mrr"] for r in rows)
        lat = statistics.mean(r["latency"] for r in rows if r["latency"])
        print(f"{label:<28} n={len(rows):>3}  Hit@8={hit:.3f}  AnswerCover={cover:.3f}  MRR={mrr:.3f}  avgLat={lat:.0f}ms")

    print("\n===== CRAG 适配评测（检索侧） =====")
    agg(results, "OVERALL")
    by_type = defaultdict(list)
    for r in results:
        by_type[r["question_type"]].append(r)
    for t, rows in sorted(by_type.items()):
        agg(rows, f"type={t}")
    by_dom = defaultdict(list)
    for r in results:
        by_dom[r["domain"]].append(r)
    for d, rows in sorted(by_dom.items()):
        agg(rows, f"domain={d}")
    errs = sum(1 for r in results if r.get("error"))
    print(f"errors={errs}")


if __name__ == "__main__":
    main()
