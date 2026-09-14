#!/usr/bin/env python3
"""Create the my_ai RAG collection with the exact schema the app expects.

Fields (must match Indexer.insert / VectorGlobalSearchChannel):
  doc_id              VARCHAR(128) primary key   -- "fileId:chunkIdx", 64-hex + ':' + 6 digits = 71 chars
  content             VARCHAR(65535)
  metadata            JSON
  embedding_context   FLOAT_VECTOR(768)          -- contextual-retrieval prefix + text
  embedding_question  FLOAT_VECTOR(768)          -- enriched question text

Usage:
  python scripts/create_my_ai_collection.py --host localhost --port 19530 --db my_xy --collection my_ai --dim 768
"""
import argparse
from pymilvus import (
    connections,
    FieldSchema,
    CollectionSchema,
    Collection,
    DataType,
    utility,
)


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--host', default='localhost')
    p.add_argument('--port', default='19530')
    p.add_argument('--db', default='my_xy')
    p.add_argument('--collection', default='my_ai')
    p.add_argument('--dim', type=int, default=768)
    args = p.parse_args()

    connections.connect(alias='default', host=args.host, port=args.port, db_name=args.db)
    name = args.collection
    if utility.has_collection(name):
        print('Collection exists:', name)
        return

    fields = [
        # NOTE: max_length=36 (Spring AI default) is NOT enough for hash-based doc_id (71 chars)
        FieldSchema(name='doc_id', dtype=DataType.VARCHAR, max_length=128, is_primary=True),
        FieldSchema(name='content', dtype=DataType.VARCHAR, max_length=65535),
        FieldSchema(name='metadata', dtype=DataType.JSON),
        FieldSchema(name='embedding_context', dtype=DataType.FLOAT_VECTOR, dim=args.dim),
        FieldSchema(name='embedding_question', dtype=DataType.FLOAT_VECTOR, dim=args.dim),
    ]
    schema = CollectionSchema(fields, description='RAG main collection, dual-vector')
    col = Collection(name, schema)
    idx = {'index_type': 'IVF_FLAT', 'metric_type': 'COSINE', 'params': {'nlist': 1024}}
    col.create_index('embedding_context', idx)
    col.create_index('embedding_question', idx)
    col.load()
    print('Created collection:', name, 'dim:', args.dim)


if __name__ == '__main__':
    main()
