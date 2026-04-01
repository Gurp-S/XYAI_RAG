# Milvus diagnostic & repair scripts

This folder contains small Python scripts to inspect, backup, create, drop and test Milvus collections.

Requirements
- Python 3.8+
- pip
- A Milvus server reachable at host/port

Setup (PowerShell)

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install --upgrade pip
pip install pymilvus
```

Common commands (PowerShell)

```powershell
# Inspect collection schema
python .\scripts\check_milvus_collection.py --host localhost --port 19530 --collection my_ai

# Backup collection
python .\scripts\backup_milvus_collection.py --host localhost --port 19530 --collection my_ai --out .\backups\my_ai_backup.npz

# Drop collection (requires typing DROP when prompted)
python .\scripts\drop_milvus_collection.py --host localhost --port 19530 --collection my_ai

# Create collection with embedding dim
python .\scripts\create_milvus_collection.py --host localhost --port 19530 --collection my_ai_correct --dim 1536 --create-index

# Quick insert and search test
python .\scripts\milvus_insert_search_test.py --host localhost --port 19530 --collection my_ai_correct --dim 1536
```

Safety notes
- Back up before performing destructive actions.
- Prefer creating a new collection and updating `src/main/resources/application.yaml` to point to it.


