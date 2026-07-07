def send_email(candidate, decision):
    name = candidate.get("name", "Candidate")
    email = candidate.get("email", "candidate@email.com")

    if decision == "ACCEPT":
        message = f"""
Dear {name},

Thank you for applying.
Based on your profile, we would like to proceed with your application.
Our HR team will contact you shortly.

Best regards,
HR Department
"""
    else:
        message = f"""
Dear {name},

Thank you for your interest in our company.
After reviewing your application, we will not proceed at this time.

We wish you success in your career.

Best regards,
HR Department
"""

    print(f"\n📧 Sending email to {email}:\n{message}")
