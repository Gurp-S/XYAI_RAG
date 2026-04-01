#!/usr/bin/env python3
"""Create a Milvus collection with a float vector field and optional index.
Usage:
  python scripts/create_milvus_collection.py --host localhost --port 19530 --collection my_ai --dim 1536
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
    p.add_argument('--collection', required=True)
    p.add_argument('--dim', type=int, required=True)
    p.add_argument('--create-index', action='store_true')
    args = p.parse_args()

    connections.connect(alias='default', host=args.host, port=args.port)
    name = args.collection
    if utility.has_collection(name):
        print('Collection exists:', name)
        return

    fields = [
        FieldSchema(name='id', dtype=DataType.INT64, is_primary=True, auto_id=True),
        FieldSchema(name='embedding', dtype=DataType.FLOAT_VECTOR, dim=args.dim),
        FieldSchema(name='metadata', dtype=DataType.VARCHAR, max_length=2048)
    ]
    schema = CollectionSchema(fields, description='collection for AI')
    col = Collection(name, schema)
    print('Created collection:', name)

    if args.create_index:
        index_params = {"index_type": "IVF_FLAT", "metric_type": "COSINE", "params": {"nlist": 1024}}
        col.create_index(field_name='embedding', index_params=index_params)
        print('Index created')


if __name__ == '__main__':
    main()

