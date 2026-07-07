def decide(candidate):
    """
    Professional HR decision logic for AI / ML candidates
    """

    # Normalize inputs
    skills = [s.lower() for s in candidate.get("skills", [])]
    education = candidate.get("education", "").lower()
    projects = " ".join(candidate.get("projects", [])).lower()
    experience = candidate.get("experience_years", 0)

    # Keywords for AI / ML exposure
    ai_keywords = [
        "ai", "artificial intelligence",
        "machine learning", "ml",
        "deep learning", "data science",
        "neural network", "nlp", "computer vision"
    ]

    # Core AI / ML technical skills
    core_skills = [
        "python", "numpy", "pandas",
        "scikit-learn", "tensorflow",
        "keras", "pytorch", "opencv"
    ]

    # STEP 1 — AI / ML exposure is mandatory
    has_ai_background = any(
        kw in " ".join(skills) or
        kw in education or
        kw in projects
        for kw in ai_keywords
    )

    if not has_ai_background:
        return "REJECT"

    # STEP 2 — Count strong technical skills
    skill_score = sum(1 for s in skills if s in core_skills)

    # STEP 3 — Final decision (simple & fair)
    if experience >= 1:
        return "ACCEPT"

    if skill_score >= 2:
        return "ACCEPT"

    return "REJECT"
