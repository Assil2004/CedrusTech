import os
import json
from pypdf import PdfReader
from openai import OpenAI

client = OpenAI()

# ---------------- PDF TEXT EXTRACTION ----------------
def extract_text_from_pdf(pdf_path):
    reader = PdfReader(pdf_path)
    text = ""
    for page in reader.pages:
        extracted = page.extract_text()
        if extracted:
            text += extracted + "\n"
    return text


# ---------------- AI CV ANALYSIS ----------------
def analyze_cv(cv_text):
    system_prompt = """
You are an HR AI system.

TASK:
Analyze the CV and return ONLY valid JSON.
DO NOT explain.
DO NOT add extra text.
DO NOT add markdown.

JSON FORMAT:
{
  "name": "Full Name",
  "skills": ["skill1", "skill2"],
  "experience_years": number,
  "decision": "YES" or "NO"
}

Decision rules:
- YES if suitable for AI / Engineering role
- NO otherwise
"""

    response = client.chat.completions.create(
        model="gpt-4.1-mini",
        messages=[
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": cv_text}
        ],
        temperature=0
    )

    return response.choices[0].message.content.strip()


# ---------------- MAIN RUNNER ----------------
if __name__ == "__main__":
    CV_FOLDER = "cvs"

    for file in os.listdir(CV_FOLDER):
        if not file.lower().endswith(".pdf"):
            continue

        print(f"\n📄 Processing CV: {file}")

        pdf_path = os.path.join(CV_FOLDER, file)
        cv_text = extract_text_from_pdf(pdf_path)

        ai_output = analyze_cv(cv_text)

        try:
            parsed = json.loads(ai_output)
            print("\n✅ CV DECISION RESULT:")
            print(json.dumps(parsed, indent=4))

        except json.JSONDecodeError:
            print("⚠️ AI returned invalid JSON:")
            print(ai_output)
