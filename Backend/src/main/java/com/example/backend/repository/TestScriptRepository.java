package com.example.backend.repository;

import com.example.backend.entity.TestScript;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TestScriptRepository extends JpaRepository<TestScript, UUID> {
    List<TestScript> findByProjectId(UUID projectId);
}
