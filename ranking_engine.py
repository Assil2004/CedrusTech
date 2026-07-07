def score_candidate(candidate):
    """
    Assigns a numeric score to an AI/ML candidate
    """

    skills = [s.lower() for s in candidate.get("skills", [])]
    education = candidate.get("education", "").lower()
    projects = " ".join(candidate.get("projects", [])).lower()
    experience = candidate.get("experience_years", 0)

    score = 0

    # AI / ML keywords
    ai_keywords = [
        "ai", "artificial intelligence", "machine learning",
        "deep learning", "data science", "nlp", "computer vision"
    ]

    # Core technical skills
    core_skills = [
        "python", "numpy", "pandas",
        "scikit-learn", "tensorflow",
        "keras", "pytorch"
    ]

    # 1️⃣ Experience (most important)
    score += experience * 3

    # 2️⃣ AI / ML projects
    for kw in ai_keywords:
        if kw in projects:
            score += 2

    # 3️⃣ Technical skills
    for skill in skills:
        if skill in core_skills:
            score += 1

    # 4️⃣ Education bonus
    if "engineering" in education or "computer" in education:
        score += 2

    return score


def rank_top_candidates(candidates, top_n=2):
    """
    Ranks accepted candidates and returns top N
    """

    scored_candidates = []

    for c in candidates:
        score = score_candidate(c)
        c["score"] = score
        scored_candidates.append(c)

    ranked = sorted(
        scored_candidates,
        key=lambda x: x["score"],
        reverse=True
    )

    return ranked[:top_n]
