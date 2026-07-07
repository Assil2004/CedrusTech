import os
import csv
import json
from cv_parser import extract_text_from_pdf, analyze_cv
from decision_engine import decide
from ranking_engine import rank_top_candidates
from email_agent import send_email

CV_FOLDER = "cvs"
LOG_FILE = "logs/decisions.csv"


def log_decision(candidate, decision):
    os.makedirs("logs", exist_ok=True)
    file_exists = os.path.isfile(LOG_FILE)

    with open(LOG_FILE, "a", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        if not file_exists:
            writer.writerow(["Name", "Decision"])
        writer.writerow([candidate.get("name", "Unknown"), decision])


def run_agent():
    print("\n🤖 HR AI AGENT STARTED\n")

    if not os.path.isdir(CV_FOLDER):
        print(f"❌ Folder '{CV_FOLDER}' not found.")
        return

    pdf_files = [f for f in os.listdir(CV_FOLDER) if f.endswith(".pdf")]

    if not pdf_files:
        print("⚠️ No CVs found in the folder.")
        return

    accepted_candidates = []

    # 🔍 STEP 1: Read & analyze CVs
    for file in pdf_files:
        print(f"📄 Processing CV: {file}")

        pdf_path = os.path.join(CV_FOLDER, file)

        try:
            cv_text = extract_text_from_pdf(pdf_path)
        except Exception as e:
            print(f"⚠️ Failed to read {file}: {e}")
            continue

        ai_output = analyze_cv(cv_text)

        try:
            candidate = json.loads(ai_output)
        except json.JSONDecodeError:
            print("⚠️ AI returned invalid JSON. Skipping CV.")
            continue

        candidate.setdefault("name", "Unknown")
        candidate.setdefault("skills", [])
        candidate.setdefault("experience_years", 0)
        candidate.setdefault("projects", [])
        candidate.setdefault("education", "")
        candidate.setdefault("email", None)

        decision = decide(candidate)

        log_decision(candidate, decision)

        if decision == "ACCEPT":
            accepted_candidates.append(candidate)

    # 🏆 STEP 2: Rank ACCEPTED candidates
    if not accepted_candidates:
        print("\n⚠️ No accepted candidates.")
        print("\n✅ HR AI AGENT FINISHED\n")
        return

    top_candidates = rank_top_candidates(accepted_candidates, top_n=2)

    print("\n🏆 TOP 2 CANDIDATES SELECTED:\n")
    for i, c in enumerate(top_candidates, 1):
        print(f"{i}. {c.get('name', 'Unknown')} — Score: {c['score']}")

    # 📧 STEP 3: Send emails ONLY to TOP 2
    print("\n📧 Sending emails to TOP 2 candidates...\n")

    for candidate in top_candidates:
        send_email(candidate, "ACCEPT")

    print("\n✅ HR AI AGENT FINISHED\n")


if __name__ == "__main__":
    run_agent()
