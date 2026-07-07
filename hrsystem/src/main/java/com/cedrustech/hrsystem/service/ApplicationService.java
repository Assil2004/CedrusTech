
package com.cedrustech.hrsystem.service;
 
import com.cedrustech.hrsystem.metrics.MetricsCollector;
import com.cedrustech.hrsystem.model.Applicant;
import com.cedrustech.hrsystem.repository.ApplicantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
 
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
 
/**
 * ApplicationService — saves job applications to the database.
 *
 * Status is always "Pending" on submission.
 * The HR AI Agent (hr_agent/agent.py) runs separately to:
 *   1. Read CVs from the cvs/ folder
 *   2. Score and rank candidates with llama3
 *   3. Update status to ACCEPT / REJECT / INTERNSHIP
 *   4. Send emails to top candidates
 *
 * CV analysis removed from this service intentionally.
 * Reason: CV scoring belongs to the HR agent workflow,
 * not the real-time web request path.
 */
@Service
public class ApplicationService {
 
    private static final Logger log =
            LoggerFactory.getLogger(ApplicationService.class);
 
    private final ApplicantRepository applicantRepository;
    private final MetricsCollector    metrics;
    private final EventLogService     eventLogService;
 
    @Value("${cv.storage.path:C:/Users/assil/Downloads/cedrustech_ai/cvs}")
    private String cvStoragePath;
 
    public ApplicationService(
            ApplicantRepository applicantRepository,
            MetricsCollector metrics,
            EventLogService eventLogService) {
        this.applicantRepository = applicantRepository;
        this.metrics             = metrics;
        this.eventLogService     = eventLogService;
    }
 
    // ─────────────────────────────────────────────────
    // Submit application — save CV + persist to DB
    // Status: always "Pending" (HR agent decides later)
    // ─────────────────────────────────────────────────
    public Map<String, Object> submitApplication(
            String        firstName,
            String        lastName,
            String        email,
            String        phone,
            long          positionId,
            String        resumeText,
            MultipartFile cvFile
    ) throws IOException {
 
        String correlationId = UUID.randomUUID().toString();
 
        metrics.applicationReceived();
 
        eventLogService.logAsync(
                EventLogService.APP_RECEIVED,
                correlationId, email,
                "applicant=" + firstName + " " + lastName,
                EventLogService.STATUS_PENDING
        );
 
        // Duplicate guard
        if (applicantRepository.existsByEmail(email)) {
            metrics.applicationFailed();
            eventLogService.logAsync(
                    EventLogService.APP_FAILED,
                    correlationId, email,
                    "Duplicate email rejected",
                    EventLogService.STATUS_FAILED
            );
            throw new IllegalArgumentException("This email has already applied.");
        }
 
        try {
            // Save CV file to disk
            String cvPath = saveCvFile(cvFile);
 
            // Always Pending — HR agent will update after analysis
            String status = "Pending";
 
            // Persist applicant
            Applicant applicant = new Applicant(
                    firstName, lastName, email, phone,
                    positionId, status, resumeText
            );
            applicantRepository.save(applicant);
 
            metrics.applicationSucceeded();
 
            eventLogService.logAsync(
                    EventLogService.APP_SAVED,
                    correlationId, email,
                    "status=" + status + " cv=" + cvPath,
                    EventLogService.STATUS_SUCCESS
            );
 
            log.info("✅ Application saved: {} {} | correlationId={}",
                    firstName, lastName, correlationId);
 
            return Map.of(
                    "success",       true,
                    "message",       "Application submitted successfully. We will review your CV and contact you soon.",
                    "status",        status,
                    "cvPath",        cvPath,
                    "correlationId", correlationId
            );
 
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            metrics.applicationFailed();
            eventLogService.logAsync(
                    EventLogService.APP_FAILED,
                    correlationId, email,
                    e.getMessage(),
                    EventLogService.STATUS_FAILED
            );
            log.error("Application save failed for {}: {}", email, e.getMessage(), e);
            throw e;
        }
    }
 
    // ─────────────────────────────────────────────────
    // Save CV file to configured storage path
    // ─────────────────────────────────────────────────
    private String saveCvFile(MultipartFile file) throws IOException {
        Path folder = Paths.get(cvStoragePath);
        Files.createDirectories(folder);
 
        String safeName = file.getOriginalFilename() != null
                ? file.getOriginalFilename().replaceAll("[^a-zA-Z0-9._-]", "_")
                : "resume.pdf";
 
        Path dest = folder.resolve(UUID.randomUUID() + "_" + safeName);
        file.transferTo(dest.toFile());
 
        log.info("CV saved → {}", dest);
        return dest.toString();
    }
}