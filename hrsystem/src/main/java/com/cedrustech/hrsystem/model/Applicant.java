package com.cedrustech.hrsystem.model;

import jakarta.persistence.*;

@Entity
@Table(
        name = "applicants",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = "email")
        }
)
public class Applicant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "applicant_id")
    private Long id;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Column(name = "phone", length = 30)
    private String phone;

    @Column(name = "position_id", nullable = false)
    private Long positionId;

    @Column(name = "application_status", nullable = false, length = 30)
    private String applicationStatus;

    @Lob
    @Column(name = "resume")
    private String resume;

    public Applicant() {}

    public Applicant(
            String firstName,
            String lastName,
            String email,
            String phone,
            Long positionId,
            String applicationStatus,
            String resume
    ) {
        this.firstName         = firstName;
        this.lastName          = lastName;
        this.email             = email;
        this.phone             = phone;
        this.positionId        = positionId;
        this.applicationStatus = applicationStatus;
        this.resume            = resume;
    }

    public Long   getId()                { return id; }
    public String getFirstName()         { return firstName; }
    public String getLastName()          { return lastName; }
    public String getEmail()             { return email; }
    public String getPhone()             { return phone; }
    public Long   getPositionId()        { return positionId; }
    public String getApplicationStatus() { return applicationStatus; }
    public String getResume()            { return resume; }

    public void setFirstName(String firstName)                 { this.firstName = firstName; }
    public void setLastName(String lastName)                   { this.lastName = lastName; }
    public void setEmail(String email)                         { this.email = email; }
    public void setPhone(String phone)                         { this.phone = phone; }
    public void setPositionId(Long positionId)                 { this.positionId = positionId; }
    public void setApplicationStatus(String applicationStatus) { this.applicationStatus = applicationStatus; }
    public void setResume(String resume)                       { this.resume = resume; }
}