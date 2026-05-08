package com.example.backend.repository;

import com.example.backend.entity.TestExecutionResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TestExecutionResultRepository extends JpaRepository<TestExecutionResult, UUID> {

    /**
     * Find all executions for a specific TestScript
     */
    List<TestExecutionResult> findByTestScriptIdOrderByExecutedAtDesc(UUID testScriptId);

    /**
     * Find all executions for a specific Project
     */
    List<TestExecutionResult> findByProjectIdOrderByExecutedAtDesc(UUID projectId);

    /**
     * Find the most recent execution for a TestScript
     */
    Optional<TestExecutionResult> findFirstByTestScriptIdOrderByExecutedAtDesc(UUID testScriptId);

    /**
     * Find all failed/error executions for a project (for debugging)
     */
    @Query("SELECT r FROM TestExecutionResult r WHERE r.project.id = :projectId AND r.status IN ('FAILED', 'ERROR') ORDER BY r.executedAt DESC")
    List<TestExecutionResult> findFailedExecutionsByProject(@Param("projectId") UUID projectId);

    /**
     * Count executions by status for a project
     */
    @Query("SELECT r.status, COUNT(r) FROM TestExecutionResult r WHERE r.project.id = :projectId GROUP BY r.status")
    List<Object[]> countByStatus(@Param("projectId") UUID projectId);
}
