"""
chunker.py — Section-Aware Chunker with Title Prefix
Path: backend/rag/chunker.py

Chunking strategy (why it beats raw character split):

  RAW (old):
    text[0:500], text[500:1000], text[1000:1500]...
    → "4. Internship Program\nCedrusTech offers" → chunk ends
    → " 3-month paid intern" → next chunk starts
    Both chunks useless alone. "Internship" query retrieves neither well.

  SECTION-AWARE (new):
    All of "4. Internship Program" content stays together.
    If it's large, split at sub-headers or sentence boundaries.
    Each chunk gets a TITLE PREFIX in the embedded text:
    "[4. Internship Program] CedrusTech offers a 3-month summer..."
    → Embedding is semantically rich → retrieval works for any phrasing.

  OVERLAP:
    Last 150 chars of chunk N prepended to chunk N+1.
    Boundary context is never lost.
"""

import re
import os
import sys

_RAG_DIR  = os.path.dirname(os.path.abspath(__file__))
_BASE_DIR = os.path.dirname(_RAG_DIR)
sys.path.insert(0, _BASE_DIR)

CHUNK_SIZE    = 900    # chars per chunk
OVERLAP_SIZE  = 150    # overlap between chunks
MIN_CHUNK     = 80     # skip trivially small chunks


def build_chunks(sections: list[dict]) -> list[dict]:
    """
    Takes sections from loader.detect_sections() and returns
    a flat list of chunk dicts ready for embedding:

    {
      "embed_text"  : str,   # what gets embedded (title + content)
      "store_text"  : str,   # what gets stored and shown to LLM
      "section"     : str,   # section title
      "section_num" : int,
      "chunk_index" : int,   # global index
      "is_cover"    : bool   # True for section 0 (CEO info)
    }
    """
    all_chunks = []
    global_idx = 0

    for section in sections:
        title       = section["title"]
        content     = section["content"]
        section_num = section["section_num"]
        is_cover    = (section_num == 0)

        # Split content into sub-chunks
        sub_chunks = _split_content(content)

        for sub in sub_chunks:
            if len(sub) < MIN_CHUNK:
                continue

            # Title prefix makes the embedding semantically richer.
            # "[Company Header & Overview] CEO: Assil Shehade..."
            # When user asks "who is the CEO", this chunk scores
            # much higher because the title provides context.
            embed_text = f"[{title}]\n{sub}"
            store_text = f"Section: {title}\n\n{sub}"

            all_chunks.append({
                "embed_text":  embed_text,
                "store_text":  store_text,
                "section":     title,
                "section_num": section_num,
                "chunk_index": global_idx,
                "is_cover":    is_cover
            })
            global_idx += 1

    print(f"✅ Chunker: {len(all_chunks)} chunks created")
    for c in all_chunks[:5]:
        preview = c["embed_text"][:80].replace("\n", " ")
        print(f"   [{c['chunk_index']}] {preview}...")

    return all_chunks


def _split_content(content: str) -> list[str]:
    """
    Split section content into chunks respecting:
      1. Paragraph boundaries (\n\n)
      2. Sentence boundaries (. \n)
      3. Overlap between chunks
    """
    if len(content) <= CHUNK_SIZE:
        return [content]   # fits in one chunk

    # Try splitting on paragraph boundaries first
    paragraphs = re.split(r'\n{2,}', content)
    paragraphs = [p.strip() for p in paragraphs if p.strip()]

    chunks  = []
    current = ""

    for para in paragraphs:
        if current and len(current) + len(para) + 2 > CHUNK_SIZE:
            chunks.append(current.strip())
            # Overlap: carry tail into next chunk
            overlap = current[-OVERLAP_SIZE:] if len(current) > OVERLAP_SIZE else current
            current = overlap + "\n\n" + para
        else:
            current += ("\n\n" if current else "") + para

    if current.strip():
        chunks.append(current.strip())

    # If still too large after paragraph split, force split by sentences
    final = []
    for chunk in chunks:
        if len(chunk) > CHUNK_SIZE * 1.5:
            final.extend(_force_split(chunk))
        else:
            final.append(chunk)

    return final


def _force_split(text: str) -> list[str]:
    """Last resort: split at sentence boundaries."""
    sentences = re.split(r'(?<=[.!?])\s+', text)
    chunks, current = [], ""

    for sent in sentences:
        if current and len(current) + len(sent) > CHUNK_SIZE:
            chunks.append(current.strip())
            overlap = current[-OVERLAP_SIZE:] if len(current) > OVERLAP_SIZE else current
            current = overlap + " " + sent
        else:
            current += (" " if current else "") + sent

    if current.strip():
        chunks.append(current.strip())

    return chunks