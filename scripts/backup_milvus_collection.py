#!/usr/bin/env python3
"""Backup a Milvus collection (ids, vectors, metadata) to a local npz/json files.
Note: This is a best-effort exporter; for very large collections consider batching and streaming.
Usage:
  python scripts/backup_milvus_collection.py --host localhost --port 19530 --collection my_ai --out backups/my_ai_backup.npz
"""
import argparse
import os
import json
import numpy as np
from pymilvus import connections, Collection, utility


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--host', default='localhost')
    p.add_argument('--port', default='19530')
    p.add_argument('--collection', required=True)
    p.add_argument('--out', required=True)
    p.add_argument('--batch', type=int, default=1000)
    args = p.parse_args()

    connections.connect(alias='default', host=args.host, port=args.port)
    name = args.collection
    if not utility.has_collection(name):
        print('Collection not found:', name)
        return

    col = Collection(name)
    num = col.num_entities
    print('Num entities:', num)
    if num == 0:
        print('Empty collection')
        return

    # Attempt to find vector field name (common name 'embedding')
    schema = col.schema
    vector_field = None
    for f in schema.fields:
        if f.dtype == f.dtype.FLOAT_VECTOR or getattr(f, 'dtype', None) == None:
            # This is heuristic; prefer field named 'embedding'
            if f.name == 'embedding':
                vector_field = f.name
                break
            if getattr(f, 'dtype', None) and getattr(f.dtype, 'name', '').lower().find('vector')!=-1:
                vector_field = f.name
    if vector_field is None:
        # fallback: try 'embedding'
        vector_field = 'embedding'

    ids = []
    vectors = []
    metas = []
    offset = 0
    while offset < num:
        limit = min(args.batch, num - offset)
        expr = f"{{}}"  # no filter
        # Use 'select' to fetch fields
        results = col.query(expr=None, output_fields=['embedding', 'metadata'], limit=limit, offset=offset)
        for r in results:
            vectors.append(r.get('embedding'))
            metas.append(r.get('metadata'))
        offset += limit
        print(f'Backed up {offset}/{num}')

    # Save vectors as npz and metadata as json
    out_dir = os.path.dirname(args.out)
    if out_dir and not os.path.exists(out_dir):
        os.makedirs(out_dir)

    np.savez_compressed(args.out, vectors=np.array(vectors, dtype=object), metadata=json.dumps(metas))
    print('Saved backup to', args.out)


if __name__ == '__main__':
    main()

