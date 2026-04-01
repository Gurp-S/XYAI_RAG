#!/usr/bin/env python3
"""Drop a Milvus collection with confirmation.
Usage:
  python scripts/drop_milvus_collection.py --host localhost --port 19530 --collection my_ai
This script requires typing DROP to confirm destructive action.
"""
import argparse
import sys
from pymilvus import connections, utility


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--host', default='localhost')
    p.add_argument('--port', default='19530')
    p.add_argument('--collection', required=True)
    args = p.parse_args()

    confirm = input(f"This will DROP collection '{args.collection}' on {args.host}:{args.port}. Type DROP to proceed: ")
    if confirm != 'DROP':
        print('Aborted')
        sys.exit(1)

    connections.connect(alias='default', host=args.host, port=args.port)
    if not utility.has_collection(args.collection):
        print('Collection does not exist')
        return
    utility.drop_collection(args.collection)
    print('Dropped collection', args.collection)


if __name__ == '__main__':
    main()

