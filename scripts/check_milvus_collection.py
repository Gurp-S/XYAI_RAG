#!/usr/bin/env python3
"""Check Milvus collection schema and vector field dimensions.
Usage:
  python scripts/check_milvus_collection.py --host localhost --port 19530 --collection my_ai
"""
import argparse
from pymilvus import connections, utility, Collection


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--host', default='localhost')
    p.add_argument('--port', default='19530')
    p.add_argument('--collection', required=True)
    args = p.parse_args()

    connections.connect(alias='default', host=args.host, port=args.port)
    name = args.collection
    print(f"Connecting to Milvus {args.host}:{args.port} -> checking collection '{name}'")
    exists = utility.has_collection(name)
    print("Has collection:", exists)
    if not exists:
        return

    col = Collection(name)
    schema = col.schema
    print("Collection schema:")
    print(schema)
    print("Fields:")
    for f in schema.fields:
        dtype = f.dtype
        print(f" - name={f.name}, type={dtype}")
        # For vector fields, attempt to extract dimension if available
        # Note: dtype may be DataType.FLOAT_VECTOR whose params are in f.params or dtype.params
        params = getattr(f, 'params', None)
        if params:
            print(f"    params: {params}")
        # Some versions expose dimension in dtype.params
        dtype_params = getattr(dtype, 'params', None)
        if dtype_params:
            print(f"    dtype params: {dtype_params}")

    try:
        num = col.num_entities
        print(f"Number of entities: {num}")
    except Exception:
        pass


if __name__ == '__main__':
    main()

