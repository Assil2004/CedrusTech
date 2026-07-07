"""
retriever.py — Powerful Multi-Query + Reranking Retriever
Path: backend/rag/retriever.py

Retrieval pipeline (3 stages):

  Stage 1 — MULTI-QUERY EMBEDDING (improves recall)
  ────────────────────────────────────────────────
  Original query:   "who is the CEO"
  Variation 1:      "CedrusTech CEO"          (company-scoped)
  Variation 2:      "CEO executive director"  (synonym expansion)
  Variation 3:      keyword bag: "ceo"        (exact keyword)

  Each variation is embedded with nomic-embed-text (~0.5s each).
  ChromaDB queried for top-8 per variation → 24 candidates.
  Duplicates removed → ~10-15 unique candidates.

  Why 3 queries instead of 1?
    Amdahl's Law: retrieval is fast (I/O bound, nomic-embed-text).
    3× queries runs on the same thread in ~1.5s total.
    Recall improves significantly: a query that misses with one
    phrasing often hits with another.

  Stage 2 — SCORE FUSION (improves precision)
  ────────────────────────────────────────────
  Each candidate gets a combined score:
    vector_score  = 1 - cosine_distance  (higher = more similar)
    keyword_score = keyword_overlap(question_keywords, chunk_text)
    final_score   = vector_score × 0.65 + keyword_score × 0.35

  Keyword scoring ensures "CEO" query boosts chunks with word "CEO"
  even if their vector score is average.

  Stage 3 — THRESHOLD FILTER + TOP-N SELECT
  ──────────────────────────────────────────
  Discard candidates with final_score < MIN_SCORE.
  Return top N by final_score.

Concurrency note:
  retrieve() is called from multiple threads (api.py ThreadPoolExecutor).
  ChromaDB collection reads are thread-safe (single shared instance).
  Ollama embeddings calls are serialized per thread by the GIL during
  HTTP I/O — but each thread has its own call, so 4 threads run 4
  embedding requests concurrently (GIL released on I/O).
"""

import os
import sys
import re
import ollama

_RAG_DIR  = os.path.dirname(os.path.abspath(__file__))
_BASE_DIR = os.path.dirname(_RAG_DIR)
sys.path.insert(0, _BASE_DIR)

from rag.vectorstore import get_collection

# ── Config ───────────────────────────────────────────
CANDIDATES_PER_QUERY = 8     # ChromaDB results per query variant
FINAL_TOP_N          = 5     # chunks sent to LLM
MIN_SCORE            = 0.30  # discard below this fused score
VECTOR_WEIGHT        = 0.65  # weight for cosine similarity
KEYWORD_WEIGHT       = 0.35  # weight for keyword overlap

# Common stop words (not useful as keywords)
_STOP_WORDS = {
    "the","is","are","was","were","a","an","of","in","to","do",
    "you","i","it","what","who","how","does","did","have","has",
    "there","can","tell","me","please","about","any","some","and",
    "or","not","no","yes","this","that","which","at","on","for"
}


def retrieve(question: str, n_results: int = FINAL_TOP_N) -> list[dict]:
    """
    Main retrieval function. Thread-safe — called concurrently.

    Returns list of dicts (sorted best-first):
      {
        text        : str,    # chunk content (shown to LLM)
        section     : str,    # section title
        section_num : int,
        vector_score: float,  # cosine similarity [0..1]
        keyword_score:float,  # keyword overlap [0..1]
        final_score : float,  # fused score
        is_cover    : bool
      }
    """
    collection = get_collection()

    # Stage 1: Generate query variations
    queries = _build_queries(question)

    # Stage 2: Embed all queries and collect candidates
    candidates = {}   # chunk_id → best score dict

    for q_text, q_label in queries:
        try:
            embedding = ollama.embeddings(
                model="nomic-embed-text",
                prompt=q_text
            )["embedding"]
        except Exception as e:
            print(f"[retriever] Embedding failed for '{q_label}': {e}")
            continue

        results = collection.query(
    query_embeddings=[embedding],
    n_results=CANDIDATES_PER_QUERY,
    include=["documents", "distances", "metadatas"]
        )

        docs      = results["documents"][0]
        distances = results["distances"][0]
        metadatas = results["metadatas"][0]
        ids       = results["ids"][0]

        for doc, dist, meta, cid in zip(docs, distances, metadatas, ids):
            vec_score = max(0.0, 1.0 - dist)   # cosine similarity

            if cid not in candidates or candidates[cid]["vector_score"] < vec_score:
                candidates[cid] = {
                    "text":         doc,
                    "section":      meta.get("section",     "Unknown"),
                    "section_num":  meta.get("section_num", -1),
                    "is_cover":     meta.get("is_cover",    False),
                    "vector_score": vec_score,
                    "query_label":  q_label
                }

    if not candidates:
        return []

    # Stage 3: Keyword scoring and score fusion
    question_keywords = _extract_keywords(question)
    scored = []

    for cid, cand in candidates.items():
        kw_score    = _keyword_score(question_keywords, cand["text"])
        final_score = (VECTOR_WEIGHT   * cand["vector_score"] +
                       KEYWORD_WEIGHT  * kw_score)

        if final_score >= MIN_SCORE:
            scored.append({
                **cand,
                "keyword_score": round(kw_score,    4),
                "final_score":   round(final_score, 4)
            })

    # Stage 4: Sort by final score, return top N
    scored.sort(key=lambda x: x["final_score"], reverse=True)
    top = scored[:n_results]

    print(f"[retriever] {len(candidates)} candidates → "
          f"{len(scored)} above threshold → top {len(top)}")
    for r in top:
        print(f"  section={r['section'][:35]:<35} "
              f"vec={r['vector_score']:.3f} "
              f"kw={r['keyword_score']:.3f} "
              f"final={r['final_score']:.3f}")

    return top



def build_context(results: list[dict]) -> str:
    """
    Assembles retrieved chunks into a context string for the LLM.
    Cover section (CEO info) is always placed first if present.
    """
    if not results:
        return "No relevant context found."

    # Prioritize cover section
    cover  = [r for r in results if r.get("is_cover")]
    others = [r for r in results if not r.get("is_cover")]
    ordered = cover + others

    parts = []
    for r in ordered:
        score = r.get("final_score", 0)
        parts.append(
            f"[{r['section']} | relevance={score:.2f}]\n"
            f"{r['text']}"
        )

    return "\n\n{'─'*50}\n\n".join(parts)


# ─────────────────────────────────────────────────────
# INTERNAL HELPERS
# ─────────────────────────────────────────────────────

def _build_queries(question: str) -> list[tuple[str, str]]:
    """
    Generate 3 query variants for multi-query retrieval.
    Returns list of (query_text, label) tuples.

    Uses fast rule-based expansion (no LLM call → no extra latency).
    LLM query expansion would add ~16s before retrieval starts.
    """
    q = question.strip()
    keywords = _extract_keywords(q)

    queries = [
        (q, "original"),
        (f"CedrusTech Solutions {q}", "company-scoped"),
    ]

    # Keyword bag query: just the important words joined
    if keywords:
        kw_query = " ".join(keywords[:5])
        if kw_query != q:
            queries.append((kw_query, "keywords"))

    # Domain-specific synonym expansions
    expansions = _get_synonyms(q)
    if expansions:
        queries.append((f"{q} {expansions}", "expanded"))

    return queries[:4]   # max 4 queries


def _get_synonyms(question: str) -> str:
    """Rule-based synonym expansion for common HR/company queries."""
    q = question.lower()
    synonyms = []

    if any(w in q for w in ["ceo", "chief", "executive", "director", "head", "founder"]):
        synonyms.extend(["CEO chief executive officer director"])
    if any(w in q for w in ["department", "dept", "division", "team", "unit"]):
        synonyms.extend(["department division organizational structure"])
    if any(w in q for w in ["intern", "internship", "student", "graduate"]):
        synonyms.extend(["internship program summer training"])
    if any(w in q for w in ["vision", "mission", "goal", "objective"]):
        synonyms.extend(["vision mission goals values strategy"])
    if any(w in q for w in ["remote", "work from home", "wfh", "hybrid"]):
        synonyms.extend(["remote work hybrid flexible schedule"])
    if any(w in q for w in ["salary", "pay", "compensation", "benefit"]):
        synonyms.extend(["salary compensation benefits payment"])
    if any(w in q for w in ["leave", "vacation", "pto", "holiday", "time off"]):
        synonyms.extend(["leave vacation PTO time off holiday"])
    if any(w in q for w in ["service", "product", "offer", "provide"]):
        synonyms.extend(["services solutions AI software cybersecurity"])

    return " ".join(synonyms)


def _extract_keywords(text: str) -> list[str]:
    """Extract meaningful keywords from a question."""
    words = re.findall(r'\b\w+\b', text.lower())
    return [w for w in words if w not in _STOP_WORDS and len(w) > 2]


def _keyword_score(keywords: list[str], text: str) -> float:
    """
    Score how many question keywords appear in the chunk text.
    Returns [0..1]: 1.0 = all keywords found, 0.0 = none found.
    """
    if not keywords:
        return 0.0

    text_lower = text.lower()
    matches    = sum(1 for kw in keywords if kw in text_lower)
    return matches / len(keywords)


# ─────────────────────────────────────────────────────
# STANDALONE TEST
# cd backend && python rag/retriever.py
# ─────────────────────────────────────────────────────

if __name__ == "__main__":
    test_questions = [
        "who is the CEO of the company",
        "what departments does the company have",
        "do you have internship program",
        "what is the vision",
        "is there remote work",
        "what are the working hours",
        "how many PTO days",
    ]

    print("CedrusTech RAG Retriever — Test\n" + "="*50)

    for q in test_questions:
        print(f"\n❓ {q}")
        results = retrieve(q, n_results=3)
        if results:
            best = results[0]
            print(f"   Best match: [{best['section']}] "
                  f"score={best['final_score']:.3f}")
            print(f"   Preview: {best['text'][:120].replace(chr(10),' ')}...")
        else:
            print("   ❌ No results")