package com.example.backend.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.persistence.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Stores execution results of test scenarios for a TestScript.
 * Tracks success/failure, timing, assertions, and reports.
 */
@Entity
@Table(name = "test_execution_results")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TestExecutionResult {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "test_script_id", nullable = false)
    @JsonIgnore
    private TestScript testScript;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    @JsonIgnore
    private Project project;

    /**
     * Execution status: PASSED, FAILED, ERROR, TIMEOUT
     */
    @Column(name = "status", length = 50, nullable = false)
    @Builder.Default
    private String status = "PENDING";

    /**
     * When the execution started
     */
    @Column(name = "executed_at")
    private LocalDateTime executedAt;

    /**
     * Total execution duration in milliseconds
     */
    @Column(name = "execution_duration_ms")
    private Long executionDurationMs;

    /**
     * Detailed scenario results as JSON array
     * Format: [{name, status, steps[], error, screenshots[]}, ...]
     */
    @Column(name = "scenario_results", columnDefinition = "LONGTEXT")
    private String scenarioResults;

    /**
     * Assertion results as JSON
     * Format: {total, passed, failed}
     */
    @Column(name = "assertion_results", columnDefinition = "TEXT")
    private String assertionResults;

    /**
     * Performance metrics as JSON
     * Format: {pageLoadTime, interactionTime, ...}
     */
    @Column(name = "performance_metrics", columnDefinition = "TEXT")
    private String performanceMetrics;

    /**
     * PDF report stored as Binary (BLOB)
     */
    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(name = "report_pdf_blob", columnDefinition = "BYTEA")
    private byte[] reportPdfBlob;

    /**
     * List of screenshot binary data
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "execution_screenshots", joinColumns = @JoinColumn(name = "execution_result_id"))
    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(name = "screenshot_blob", columnDefinition = "BYTEA")
    @Builder.Default
    private List<byte[]> screenshotBlobs = new ArrayList<>();

    /**
     * Error details if execution failed
     */
    @Column(name = "error_details", columnDefinition = "TEXT")
    private String errorDetails;

    /**
     * Stdout + stderr logs from execution
     */
    @Column(name = "logs", columnDefinition = "LONGTEXT")
    private String logs;

    /**
     * Audit: which scenarios were selected/executed by user
     * Stored as JSON array of scenario IDs
     */
    @Column(name = "selected_scenario_ids", columnDefinition = "TEXT")
    private String selectedScenarioIds;

    /**
     * Username of person who triggered execution
     */
    @Column(name = "executed_by", length = 255)
    private String executedBy;

    /**
     * Creation timestamp
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (executedAt == null) {
            executedAt = LocalDateTime.now();
        }
        if (status == null) {
            status = "PENDING";
        }
    }
}
