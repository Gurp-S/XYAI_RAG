#!/usr/bin/env python3
"""Upload missing CRAG corpus files (one per request, with existence check)."""
import hashlib
import json
import subprocess
import sys
import time
from pathlib import Path

BASE = Path(__file__).resolve().parent
CORPUS = BASE / "corpus"
TOKEN = open(r"C:/Users/48522/AppData/Local/Temp/xy_token.txt").read().strip()


def sha256_file(p: Path) -> str:
    h = hashlib.sha256()
    h.update(p.read_bytes())
    return h.hexdigest()


def in_milvus(file_hash: str) -> bool:
    from pymilvus import connections, Collection
    if not hasattr(in_milvus, "_conn"):
        connections.connect(host="localhost", port="19530", db_name="my_xy")
    c = Collection("my_ai")
    res = c.query(expr=f'doc_id like "{file_hash}%"', output_fields=["doc_id"], limit=1)
    return len(res) > 0


def upload(p: Path):
    r = subprocess.run([
        "curl", "-s", "--noproxy", "*", "-X", "POST",
        "http://localhost:8080/upload/up",
        "-H", "Authorization: Bearer " + TOKEN,
        "-F", f"file=@{p};type=text/plain;filename={p.name}",
        "-F", "collectionName=my_ai", "--max-time", "120",
    ], capture_output=True, timeout=140)
    try:
        body = json.loads(r.stdout.decode("utf-8", "ignore"))
        return body.get("code")
    except Exception:
        return f"http{r.returncode}:{r.stdout[:80]!r}"


def main():
    files = sorted(CORPUS.glob("*.txt"))
    print("corpus files:", len(files))
    missing, present, failed = [], 0, []
    for i, p in enumerate(files):
        h = sha256_file(p)
        try:
            if in_milvus(h):
                present += 1
                continue
        except Exception as e:
            print("milvus check err:", e)
        missing.append((p, h))
        if (i + 1) % 40 == 0:
            print(f"scanned {i+1}...")
    print(f"in-milvus={present} missing={len(missing)}")

    for i, (p, _) in enumerate(missing):
        code = upload(p)
        if code != 200:
            failed.append((p.name, code))
        if (i + 1) % 20 == 0:
            print(f"uploaded {i+1}/{len(missing)}")
        time.sleep(0.3)
    print("UPLOAD DONE. failed:", len(failed))
    for name, code in failed[:5]:
        print("  fail:", name, code)
    (BASE / "upload_state.json").write_text(json.dumps(
        {"present": present, "uploaded": len(missing) - len(failed), "failed": len(failed)}),
        encoding="utf-8")


if __name__ == "__main__":
    main()
