"""
loader.py — Smart PDF Loader with Structure Detection
Path: backend/rag/loader.py

Extracts text from PDF while preserving:
  - Section hierarchy (numbered sections + sub-headers)
  - Bullet points and lists
  - Page numbers for metadata
  - Cover page / company header as special section
"""

import os
import re
from pypdf import PdfReader

_RAG_DIR  = os.path.dirname(os.path.abspath(__file__))
_BASE_DIR = os.path.dirname(_RAG_DIR)
PDF_PATH  = os.path.join(_BASE_DIR, "data", "CedrusTech Solutions.pdf")


# Patterns that indicate a section header
_NUMBERED_SECTION = re.compile(r'^\d{1,2}\.\s+\S+', re.MULTILINE)
_SUB_HEADER       = re.compile(
    r'^(?:Welcome Message|About |Our Mission|Our Vision|Our Core|'
    r'Company History|Organizational|Equal Employment|Employee Class|'
    r'Full-Time|Part-Time|Temporary|Probationary|Standard Work|'
    r'Remote Work|Paid Time Off|Public Holidays|Sick Leave|'
    r'Performance &|Separation &|Acknowledgement)',
    re.MULTILINE
)


def load_pdf(pdf_path: str = PDF_PATH) -> list[dict]:
    """
    Load PDF and return list of page dicts:
      { page_num: int, text: str }
    Cleans common OCR artifacts.
    """
    reader = PdfReader(pdf_path)
    pages  = []

    for i, page in enumerate(reader.pages):
        raw = page.extract_text() or ""
        cleaned = _clean(raw)
        if cleaned.strip():
            pages.append({"page_num": i + 1, "text": cleaned})

    print(f"✅ Loader: {len(pages)} pages extracted from {os.path.basename(pdf_path)}")
    return pages


def load_full_text(pdf_path: str = PDF_PATH) -> str:
    """Returns the entire PDF as one cleaned string."""
    pages = load_pdf(pdf_path)
    return "\n\n".join(p["text"] for p in pages)


def detect_sections(text: str) -> list[dict]:
    """
    Splits text into logical sections based on numbered headers.
    Returns list of:
      { title: str, content: str, section_num: int | None }

    Special section 0 = cover page / company header
    (contains CEO name, company name, effective date)
    """
    # Find all numbered section starts
    matches = list(_NUMBERED_SECTION.finditer(text))

    sections = []

    # Section 0: everything before the first numbered section
    # (cover page — contains CEO, company name, date)
    if matches:
        header_text = text[:matches[0].start()].strip()
        if header_text:
            sections.append({
                "title":       "Company Header & Overview",
                "content":     header_text,
                "section_num": 0
            })

    # Numbered sections
    for i, match in enumerate(matches):
        title   = match.group(0).strip()
        start   = match.end()
        end     = matches[i + 1].start() if i + 1 < len(matches) else len(text)
        content = text[start:end].strip()

        # Extract section number
        num = int(re.match(r'^(\d+)', title).group(1))

        sections.append({
            "title":       title,
            "content":     content,
            "section_num": num
        })

    print(f"✅ Loader: {len(sections)} sections detected")
    for s in sections:
        print(f"   [{s['section_num']}] {s['title'][:60]} "
              f"({len(s['content'])} chars)")

    return sections


def _clean(text: str) -> str:
    """Remove common PDF extraction artifacts."""
    # Remove lone page numbers
    text = re.sub(r'^\s*\d+\s*$', '', text, flags=re.MULTILINE)
    # Normalize multiple spaces
    text = re.sub(r'[ \t]{2,}', ' ', text)
    # Normalize multiple blank lines
    text = re.sub(r'\n{3,}', '\n\n', text)
    # Remove hyphenation artifacts (word- \nbreak)
    text = re.sub(r'-\n(\w)', r'\1', text)
    return text.strip()