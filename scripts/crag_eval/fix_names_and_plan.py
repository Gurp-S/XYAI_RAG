#!/usr/bin/env python3
"""Idempotent: rename corpus to ASCII-safe names (old or new), update manifest, plan uploads."""
import hashlib
import json
import re
import subprocess
from pathlib import Path

BASE = Path(__file__).resolve().parent
CORPUS = BASE / "corpus"


def safe_name(stem: str) -> str:
    s = re.sub(r"[^A-Za-z0-9_-]+", "-", stem).strip("-")
    return (s[:80] or "page") + ".txt"


def resolve(stem_page: str) -> Path | None:
    """manifest 里的页面名 → 磁盘实际文件（可能是旧名或已改的新名）"""
    old = CORPUS / stem_page
    if old.exists():
        return old
    new = CORPUS / safe_name(Path(stem_page).stem)
    if new.exists():
        return new
    return None


def indexed(file_hash: str) -> bool:
    key = f"xyai:file:hash:{{{file_hash}}}"
    r = subprocess.run(["redis-cli", "exists", key], capture_output=True, text=True, timeout=10)
    return r.stdout.strip() == "1"


def main():
    manifest = json.loads((BASE / "eval_manifest.json").read_text(encoding="utf-8"))
    todo, done, missing = [], 0, []
    for item in manifest:
        new_pages = []
        for page in item["pages"]:
            p = resolve(page)
            if p is None:
                missing.append(page)
                continue
            stem = p.stem
            want = safe_name(stem)
            wantp = CORPUS / want
            if wantp != p:
                p = p.rename(wantp)
            h = hashlib.sha256(p.read_bytes()).hexdigest()
            new_pages.append(want)
            if indexed(h):
                done += 1
            else:
                todo.append({"file": want, "hash": h})
        item["pages"] = new_pages
    (BASE / "eval_manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=1), encoding="utf-8")
    (BASE / "todo_upload.json").write_text(json.dumps(todo), encoding="utf-8")
    print(f"done={done} todo={len(todo)} missing={len(missing)}")
    for m in missing[:5]:
        print("  missing:", m)


if __name__ == "__main__":
    main()
