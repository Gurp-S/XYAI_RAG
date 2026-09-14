#!/usr/bin/env python3
"""Probe the dedicated embedding endpoint (OpenAI compatible /embeddings).

Reads base-url / api-key / model from application-emb.yaml (keeps the key
out of shell history), then:
  1. calls POST /v1/embeddings with the configured model
  2. prints the returned vector dimension
  3. optionally verifies the `dimensions` parameter is honored

Usage:
  python scripts/probe_embedding.py [--with-dims 1024]
"""
import argparse
import json
import re
import urllib.request
import urllib.error
from pathlib import Path

YAML_PATH = Path(__file__).resolve().parent.parent / "src/main/resources/application-emb.yaml"


def load_conf():
    text = YAML_PATH.read_text(encoding="utf-8")
    base = re.search(r"base-url:\s*(\S+)", text).group(1)
    key = re.search(r"api-key:\s*(\S+)", text).group(1)
    model = re.search(r"model:\s*(\S+)", text).group(1)
    dims = re.search(r"dimensions:\s*(\d+)", text)
    return base.rstrip("/"), key, model, int(dims.group(1)) if dims else None


def call(base, key, model, payload_dims=None):
    body = {"model": model, "input": ["向量维度探测试句"]}
    if payload_dims:
        body["dimensions"] = payload_dims
    req = urllib.request.Request(
        base + "/v1/embeddings",
        data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + key},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            data = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        print("HTTP", e.code, e.read().decode("utf-8", "ignore")[:500])
        return None
    emb = data["data"][0]["embedding"]
    return len(emb)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--with-dims", type=int, default=None,
                    help="若指定，则额外发一次带 dimensions 参数的请求验证 MRL 支持")
    args = ap.parse_args()

    base, key, model, _ = load_conf()
    print("endpoint :", base)
    print("model    :", model)

    d = call(base, key, model)
    if d is None:
        raise SystemExit(1)
    print("default dimension:", d)

    if args.with_dims:
        d2 = call(base, key, model, args.with_dims)
        print("dimensions=%s ->" % args.with_dims, d2)
        print("MRL supported:", d2 == args.with_dims)


if __name__ == "__main__":
    main()
