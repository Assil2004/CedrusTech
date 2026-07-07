package com.cedrustech.hrsystem.repository;

import com.cedrustech.hrsystem.model.Applicant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ApplicantRepository extends JpaRepository<Applicant, Long> {

    boolean existsByEmail(String email);
}