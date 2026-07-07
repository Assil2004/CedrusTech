"""
chatbot.py — CedrusTech RAG Chatbot (Powerful Version)
Path: backend/chatbot.py

Uses the full RAG pipeline:
  question → retriever.py (multi-query + score fusion) → LLM

No hardcoded facts needed — the improved RAG finds them reliably.

Concurrency design:
  Called from api.py via ThreadPoolExecutor(4).
  Each thread has its own call stack but shares:
    - _collection (ChromaDB singleton — thread-safe reads)
    - _histories  (ConcurrentHashMap equivalent with per-session locks)
  Per-session locks ensure history is consistent without
  serializing across sessions (Amdahl: serial fraction ≈ 0).
"""

import os
import sys
import threading
import ollama

_BASE_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, _BASE_DIR)

from rag.retriever import retrieve, build_context

# ── Thread-safe session histories ────────────────────
_histories:     dict = {}
_session_locks: dict = {}
_global_lock         = threading.Lock()
MAX_HISTORY          = 10


def _get_lock(sid: str) -> threading.Lock:
    """Double-checked locking — avoids bottleneck on global lock."""
    if sid not in _session_locks:
        with _global_lock:
            if sid not in _session_locks:
                _session_locks[sid] = threading.Lock()
    return _session_locks[sid]


def _save_history(sid: str, question: str, answer: str):
    lock = _get_lock(sid)
    with lock:
        h = _histories.setdefault(sid, [])
        h.append({"role": "user",      "content": question})
        h.append({"role": "assistant", "content": answer})
        if len(h) > MAX_HISTORY * 2:
            del h[:2]


# ── Main function ─────────────────────────────────────

def ask_chatbot(question: str, session_id: str = "default") -> str:

    try:
        question = question.strip()
        if not question:
            return "Please enter a valid question."

        print(f"\n[{session_id}] ▶ QUESTION: {question}")

        # Greeting shortcut — no RAG call needed
        if question.lower().strip("!.,?") in {
            "hi", "hello", "hey", "hii", "hiii",
            "greetings", "good morning", "good evening"
        }:
            answer = ("Hello! Welcome to CedrusTech Solutions. "
                      "How can I help you today? Ask me about our "
                      "services, internship program, departments, "
                      "careers, or anything about CedrusTech!")
            _save_history(session_id, question, answer)
            return answer

        # History snapshot (thread-safe)
        lock = _get_lock(session_id)
        with lock:
            history = list(_histories.get(session_id, []))

        # ── RAG: multi-query retrieval + score fusion ──
        results = retrieve(question, n_results=5)
        context = build_context(results)

        print(f"[{session_id}] Context built from {len(results)} chunks")

        # ── System prompt ──────────────────────────────
        system_prompt = f"""You are the official AI Assistant for CedrusTech Solutions.

RETRIEVED COMPANY KNOWLEDGE (use this to answer all questions):
{context}

YOUR RULES:
1. Answer using ONLY the retrieved knowledge above.
2. Be direct and factual — give the actual answer from the context.
3. If the answer is clearly in the context, state it confidently.
4. Never apologize unnecessarily — just answer.
5. For questions not covered in context, say:
   "I don't have that specific detail. Contact us at CedrusTech.hr@gmail.com"
6. Keep answers clear and professional.
7. For list questions (departments, services), give the complete list from context."""

        # Build messages
        messages = [{"role": "system", "content": system_prompt}]
        messages.extend(history)
        messages.append({"role": "user", "content": question})

        # ── Ollama inference ───────────────────────────
        print(f"[{session_id}] Calling llama3...")
        output = ollama.chat(model="llama3", messages=messages)
        answer = output["message"]["content"].strip()

        if not answer:
            answer = ("I could not find that information. "
                      "Please contact CedrusTech.hr@gmail.com")

        print(f"[{session_id}] ✅ ANSWER: {answer[:120]}")
        _save_history(session_id, question, answer)
        return answer

    except Exception as e:
        print(f"[{session_id}] ❌ ERROR: {e}")
        import traceback; traceback.print_exc()
        return "I encountered an error. Please try again."