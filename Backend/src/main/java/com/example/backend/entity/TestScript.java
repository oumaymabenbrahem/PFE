package com.example.backend.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

@Entity
@Table(name = "test_scripts", indexes = {
    @Index(name = "idx_project_id", columnList = "project_id"),
    @Index(name = "idx_generated_at", columnList = "generated_at")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TestScript {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    @JsonIgnore
    private Project project;

    @Column(name = "script_content", columnDefinition = "TEXT", nullable = false)
    private String scriptContent;

    @Column(name = "scenarios", columnDefinition = "TEXT")
    private String scenarios;  // Stocké en JSON string

    @Column(name = "elements_count")
    private Integer elementsCount;

    @Column(name = "framework", length = 50)
    private String framework;

    @Column(name = "statut", length = 50)
    private String statut;  // GENERE, ERREUR

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "generated_at", updatable = false)
    private LocalDateTime generatedAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Deprecated
    @Column(nullable = true)
    private String name;

    @Deprecated
    @Column(columnDefinition = "TEXT")
    private String script;

    @Deprecated
    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.generatedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        if (this.statut == null) {
            this.statut = "GENERE";
        }
        if (this.framework == null) {
            this.framework = "selenium";
        }
        if (this.name == null) {
            this.name = "test_" + System.currentTimeMillis();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Récupère les scénarios en tant que liste
     */
    public List<String> getScenariosList() {
        if (scenarios == null || scenarios.isEmpty()) {
            return new java.util.ArrayList<>();
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(scenarios, 
                new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return new java.util.ArrayList<>();
        }
    }

    /**
     * Définit les scénarios à partir d'une liste
     */
    public void setScenariosList(List<String> scenariosList) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            this.scenarios = mapper.writeValueAsString(scenariosList);
        } catch (Exception e) {
            this.scenarios = "[]";
        }
    }

    /**
     * Stocke les scénarios en tant que string JSON
     */
    public void setScenariosAsString(java.util.List<String> scenariosList) {
        setScenariosList(scenariosList);
    }
}
