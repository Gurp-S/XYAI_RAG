#!/usr/bin/env python3
"""Insert a test vector and run a search to validate dimension matches the collection schema.
Usage:
  python scripts/milvus_insert_search_test.py --host localhost --port 19530 --collection my_ai --dim 1536
"""
import argparse
import numpy as np
from pymilvus import connections, utility, Collection


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--host', default='localhost')
    p.add_argument('--port', default='19530')
    p.add_argument('--collection', required=True)
    p.add_argument('--dim', type=int, required=True)
    args = p.parse_args()

    connections.connect(alias='default', host=args.host, port=args.port)
    name = args.collection
    if not utility.has_collection(name):
        print('Collection does not exist:', name)
        return

    col = Collection(name)
    vec = np.random.rand(args.dim).astype('float32').tolist()
    # Insert
    entities = [[], [vec], []]  # matches schema [id(auto), embedding, metadata]
    try:
        res = col.insert([[], [vec], []])
        print('Insert result:', res)
    except Exception as e:
        print('Insert failed:', e)
        return

    col.load()
    results = col.search([vec], 'embedding', param={'metric_type': 'COSINE', 'params': {'nprobe': 10}}, limit=1)
    print('Search results length:', len(results))
    for hits in results:
        for h in hits:
            print('hit id', h.id, 'score', h.score)


if __name__ == '__main__':
    main()

