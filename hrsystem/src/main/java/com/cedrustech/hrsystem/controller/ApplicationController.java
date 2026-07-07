package com.cedrustech.hrsystem.controller;

import com.cedrustech.hrsystem.service.ApplicationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.concurrent.*;

/**
 * POST /apply — job application endpoint.
 *
 * Mirrors Python FastAPI /apply exactly:
 *   - first_name, last_name, email, phone (form fields)
 *   - position_id (form field)
 *   - resume_text (optional form field)
 *   - cv (file upload)
 *
 * Returns CompletableFuture<ResponseEntity<>> for async processing.
 * The appExecutor thread does file I/O + DB write
 * while Tomcat handles other requests concurrently.
 */
@RestController
@CrossOrigin(origins = "*")
public class ApplicationController {

    private static final Logger log =
            LoggerFactory.getLogger(ApplicationController.class);

    private final ApplicationService applicationService;
    private final ExecutorService    appExecutor;

    public ApplicationController(
        ApplicationService applicationService,
        @Qualifier("appExecutor") ThreadPoolExecutor appExecutor
    ) {
        this.applicationService = applicationService;
        this.appExecutor        = appExecutor;
    }

    // ─────────────────────────────────────────────────
    // POST /apply — async CompletableFuture response
    // ─────────────────────────────────────────────────
    @PostMapping(value = "/apply", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CompletableFuture<ResponseEntity<Map<String, Object>>> applyJob(

        @RequestParam("first_name")                          String        firstName,
        @RequestParam("last_name")                           String        lastName,
        @RequestParam("email")                               String        email,
        @RequestParam(value = "phone",       required = false) String      phone,
        @RequestParam("position_id")                         long          positionId,  // FIX: was int, must be long to match ApplicationService
        @RequestParam(value = "resume_text", required = false) String      resumeText,
        @RequestPart("cv")                                   MultipartFile cv

    ) {
        log.info("Application received from: {} {} <{}>",
                firstName, lastName, email);

        return CompletableFuture.supplyAsync(() -> {
            try {
                Map<String, Object> result = applicationService.submitApplication(
                    firstName, lastName, email, phone,
                    positionId, resumeText, cv
                );
                return ResponseEntity.ok(result);

            } catch (IllegalArgumentException e) {
                // Duplicate email guard
                log.warn("Duplicate application rejected: {}", email);
                return ResponseEntity.badRequest()
                        .<Map<String, Object>>body(Map.of(
                            "success", false,
                            "detail",  e.getMessage()
                        ));

            } catch (Exception e) {
                log.error("Application error for {}: {}", email, e.getMessage());
                return ResponseEntity.internalServerError()
                        .<Map<String, Object>>body(Map.of(
                            "success", false,
                            "detail",  e.getMessage()
                        ));
            }
        }, appExecutor);
    }
}