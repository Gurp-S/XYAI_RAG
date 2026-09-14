#!/usr/bin/env python3
"""CRAG 评测集采样与证据提取。

1. 从官方 index（2700 题）按 domain×question_type 分层抽样
2. 下载每题的完整 JSON（含 search_results 网页证据）
3. 把每个证据页面落成独立 .txt（文件名 = 页面名），输出 eval_manifest.json

Usage:
  python scripts/crag_eval/prepare_crag.py --num 60
"""
import argparse
import bz2
import json
import re
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

BASE = Path(__file__).resolve().parent
DATA = BASE / "data"
OUT = BASE / "corpus"
HF = "https://hf-mirror.com/datasets/Quivr/CRAG/resolve/main/crag_task_1_and_2"

KEEP_TYPES = {"simple", "simple_w_condition", "comparison"}


def load_index():
    items = []
    for line in bz2.open(DATA / "index.jsonl.bz2", "rt", encoding="utf-8"):
        it = json.loads(line)
        if it.get("question_type") in KEEP_TYPES and it.get("answer_type") == "valid":
            items.append(it)
    return items


def stratified_sample(items, num):
    # domain 均衡、type 尽量覆盖
    by_domain = {}
    for it in items:
        by_domain.setdefault(it["domain"], []).append(it)
    domains = sorted(by_domain)
    per = max(1, num // len(domains))
    picked = []
    for i, dom in enumerate(domains):
        pool = sorted(by_domain[dom], key=lambda x: x["interaction_id"])
        take = per + (1 if i < num - per * len(domains) else 0)
        picked.extend(pool[:take])
    return picked[:num]


def sanitize(name: str) -> str:
    name = re.sub(r'[\\/:*?"<>|\s]+', "_", name).strip("_")
    return (name[:60] or "page") + ".txt"


def download_one(rec):
    iid = rec["interaction_id"]
    path = DATA / f"{iid}.json"
    if path.exists() and path.stat().st_size > 1000:
        return iid, "cached"
    url = f"{HF}/{iid}.json"
    for attempt in range(3):
        try:
            import subprocess
            r = subprocess.run(
                ["curl", "-sL", "-m", "120", "-A", "Mozilla/5.0", "-o", str(path), url],
                capture_output=True, timeout=150)
            if r.returncode == 0 and path.exists() and path.stat().st_size > 1000:
                return iid, "ok"
            raise RuntimeError(f"curl rc={r.returncode} size={path.stat().st_size if path.exists() else 0}")
        except Exception as e:
            if attempt == 2:
                return iid, f"fail:{e}"
            time.sleep(2 * (attempt + 1))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--num", type=int, default=60)
    args = ap.parse_args()

    items = load_index()
    print(f"index 有效题: {len(items)} (types={sorted(KEEP_TYPES)})")
    picked = stratified_sample(items, args.num)
    print(f"抽样 {len(picked)} 题, domain 分布:",
          {d: sum(1 for p in picked if p['domain'] == d) for d in set(p['domain'] for p in picked)})

    with ThreadPoolExecutor(max_workers=6) as ex:
        results = list(ex.map(download_one, picked))
    fails = [r for r in results if r[1].startswith("fail")]
    print(f"下载完成: {len(results) - len(fails)} 成功 / {len(fails)} 失败")
    picked = [p for p in picked if (p["interaction_id"], "ok") in results
              or (p["interaction_id"], "cached") in results]

    # 提取证据页面
    OUT.mkdir(exist_ok=True)
    manifest = []
    for rec in picked:
        iid = rec["interaction_id"]
        path = DATA / f"{iid}.json"
        if not path.exists():
            continue
        try:
            data = json.load(open(path, encoding="utf-8"))
        except Exception as e:
            print("json load fail:", iid, e)
            continue
        # 结构：{interaction_id, search_results: [{page_name, page_url, page_snippet}]}
        search_results = data.get("search_results") or []
        if not search_results:
            continue
        pages = []
        for sr in search_results:
            page_name = (sr.get("page_name") or "").strip()
            page_result = (sr.get("page_snippet") or sr.get("page_result") or "").strip()
            if not page_result or len(page_result) < 50:
                continue
            fname = sanitize(page_name or f"page_{len(pages)}")
            # 不同题同名页面内容可能不同：文件名并入 iid 前缀保证唯一
            fname = f"{iid[:8]}_{fname}"
            (OUT / fname).write_text(page_result, encoding="utf-8")
            pages.append(fname)
        if pages:
            manifest.append({
                "interaction_id": iid,
                "query": rec["query"],
                "answer": rec["answer"],
                "domain": rec["domain"],
                "question_type": rec["question_type"],
                "pages": pages,
            })

    (BASE / "eval_manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=1), encoding="utf-8")
    n_files = len(list(OUT.glob("*.txt")))
    print(f"证据文件: {n_files} 个; 可评题目: {len(manifest)} 道")
    print(f"manifest: {BASE / 'eval_manifest.json'}")


if __name__ == "__main__":
    main()
