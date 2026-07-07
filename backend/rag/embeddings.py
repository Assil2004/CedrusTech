"""
embeddings.py — Build ChromaDB Knowledge Base
Path: backend/rag/embeddings.py

Run from backend/ OR from backend/rag/ — both work:
  cd backend
  python rag/embeddings.py
       OR
  cd backend/rag
  python embeddings.py
"""

import os, sys, time

# Add BOTH backend/ and backend/rag/ to sys.path
# so imports work regardless of where you run from
_RAG_DIR  = os.path.dirname(os.path.abspath(__file__))   # backend/rag/
_BASE_DIR = os.path.dirname(_RAG_DIR)                     # backend/
sys.path.insert(0, _BASE_DIR)
sys.path.insert(0, _RAG_DIR)

import chromadb, ollama

# Direct imports (no rag. prefix needed — rag/ is in sys.path)
from loader  import load_full_text, detect_sections, PDF_PATH
from chunker import build_chunks

CHROMA_DIR = os.path.join(_BASE_DIR, "chroma_db")

print("=" * 60)
print("  CedrusTech Knowledge Base Builder")
print("=" * 60)
print(f"  PDF    : {PDF_PATH}")
print(f"  Chroma : {CHROMA_DIR}")
print()

# ── Step 1: Load PDF ─────────────────────────────────
print("Step 1: Loading PDF...")
full_text = load_full_text(PDF_PATH)
print(f"  {len(full_text)} characters extracted\n")

# ── Step 2: Detect sections ──────────────────────────
print("Step 2: Detecting sections...")
sections = detect_sections(full_text)
print()

# ── Step 3: Build chunks ─────────────────────────────
print("Step 3: Building chunks...")
chunks = build_chunks(sections)
print()

# ── Verify key content ────────────────────────────────
ceo_ok    = any("Assil" in c["store_text"] or "CEO" in c["store_text"] for c in chunks)
dept_ok   = any("Department" in c["store_text"] for c in chunks)
intern_ok = any("internship" in c["store_text"].lower() for c in chunks)
vision_ok = any("Vision" in c["store_text"] for c in chunks)

print("Content check:")
print(f"  CEO info      : {'✅' if ceo_ok    else '❌ NOT FOUND'}")
print(f"  Departments   : {'✅' if dept_ok   else '❌ NOT FOUND'}")
print(f"  Internship    : {'✅' if intern_ok else '❌ NOT FOUND'}")
print(f"  Vision        : {'✅' if vision_ok else '❌ NOT FOUND'}")
print()

# ── Step 4: ChromaDB setup ───────────────────────────
print("Step 4: Setting up ChromaDB...")
client = chromadb.PersistentClient(path=CHROMA_DIR)

try:
    client.delete_collection("cedrustech_knowledge")
    print("  Old collection deleted")
except Exception:
    print("  No existing collection")

collection = client.create_collection(
    name="cedrustech_knowledge",
    metadata={"hnsw:space": "cosine"}
)
print("  New collection created ✅\n")

# ── Step 5: Embed and store ──────────────────────────
print(f"Step 5: Embedding {len(chunks)} chunks...")
start = time.time()

for c in chunks:
    idx = c["chunk_index"]
    response = ollama.embeddings(
        model="nomic-embed-text",
        prompt=c["embed_text"]
    )
    collection.add(
        ids        = [str(idx)],
        embeddings = [response["embedding"]],
        documents  = [c["store_text"]],
        metadatas  = [{
            "section":     c["section"],
            "section_num": c["section_num"],
            "is_cover":    c["is_cover"],
            "source":      "CedrusTech Solutions.pdf"
        }]
    )
    print(f"  [{idx+1}/{len(chunks)}] {c['section'][:45]:<45} "
          f"{time.time()-start:.1f}s", end="\r")

print()
print()
print("=" * 60)
print(f"  ✅ Done! {collection.count()} chunks stored")
print(f"  Path : {CHROMA_DIR}")
print()
print("  Next: uvicorn api:app --host 0.0.0.0 --port 8000")
print("=" * 60)