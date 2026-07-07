"""
vectorstore.py — ChromaDB Singleton
Path: backend/rag/vectorstore.py

Provides a single shared ChromaDB collection instance.
Thread-safe: ChromaDB PersistentClient is safe for concurrent reads.
Multiple threads (from api.py ThreadPoolExecutor) can query
simultaneously — ChromaDB uses its own internal locking.
"""

import os
import threading
import chromadb

_RAG_DIR    = os.path.dirname(os.path.abspath(__file__))
_BASE_DIR   = os.path.dirname(_RAG_DIR)
CHROMA_DIR  = os.path.join(_BASE_DIR, "chroma_db")

# Singleton — created once, shared across all threads
_client:     chromadb.PersistentClient = None
_collection                             = None
_init_lock  = threading.Lock()


def get_collection():
    """
    Thread-safe singleton getter.
    Double-checked locking: fast path avoids lock after init.

    Why singleton?
      ChromaDB PersistentClient opens file handles to SQLite
      and HNSW index files. Creating one per request would
      exhaust file descriptors under concurrent load (50 clients).
      One shared instance handles all concurrent queries safely.
    """
    global _client, _collection

    if _collection is not None:
        return _collection          # fast path — no lock needed

    with _init_lock:
        if _collection is None:     # double-check inside lock
            _client     = chromadb.PersistentClient(path=CHROMA_DIR)
            _collection = _client.get_collection(name="cedrustech_knowledge")
            count       = _collection.count()
            print(f"✅ VectorStore: {count} chunks | path={CHROMA_DIR}")

    return _collection