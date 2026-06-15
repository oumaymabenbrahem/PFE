package com.example.backend.controller;

import com.example.backend.dto.ProjectRequest;
import com.example.backend.dto.ProjectResponse;
import com.example.backend.entity.TestScript;
import com.example.backend.service.ProjectService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;
import java.util.ArrayList;
import java.util.Map;

@RestController
@RequestMapping("/api/projects")
@CrossOrigin(origins = {"http://localhost:4200", "http://localhost:3000"})
@Slf4j
public class ProjectController {

    @Autowired
    private ProjectService projectService;

    // Créer un nouveau projet avec fichier uploadé

    @PostMapping
    public ResponseEntity<?> createProject(
            @Valid @RequestPart("project") ProjectRequest projectRequest,
            @RequestPart(value = "fichier", required = false) MultipartFile fichier) {
        try {
            UUID userId = getCurrentUserId();
            log.info("Création de projet par: {}", userId);

            ProjectResponse response = projectService.createProject(projectRequest, fichier, userId);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException e) {
            log.warn("Erreur lors de la création du projet: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse("Erreur: " + e.getMessage()));
        } catch (Exception e) {
            log.error("Erreur interne lors de la création du projet", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("Erreur interne du serveur"));
        }
    }

    // Récupère tous les projets de l'utilisateur connecté

    @GetMapping
    public ResponseEntity<?> getMyProjects() {
        try {
            UUID userId = getCurrentUserId();
            log.info("Récupération des projets pour: {}", userId);

            List<ProjectResponse> projects = projectService.getProjectsByUser(userId);
            return ResponseEntity.ok(projects);
        } catch (Exception e) {
            log.error("Erreur lors de la récupération des projets", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("Erreur interne du serveur"));
        }
    }

   // Récupèrer les détails d'un projet

    @GetMapping("/{id}")
    public ResponseEntity<?> getProject(@PathVariable UUID id) {
        try {
            log.info("Récupération du projet: {}", id);
            ProjectResponse project = projectService.getProjectById(id);
            return ResponseEntity.ok(project);
        } catch (Exception e) {
            log.error("Erreur lors de la récupération du projet", e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse("Projet non trouvé"));
        }
    }

   //Met à jour un projet

    @PutMapping(value = "/{id}", consumes = { "multipart/form-data" })
    public ResponseEntity<?> updateProject(
            @PathVariable UUID id,
            @RequestPart("project") ProjectRequest projectRequest,
            @RequestPart(value = "fichier", required = false) MultipartFile fichier) {
        try {
            UUID userId = getCurrentUserId();
            log.info("Mise à jour du projet: {} par: {} avec fichier: {}", id, userId, (fichier != null));

            ProjectResponse response = projectService.updateProject(id, projectRequest, fichier, userId);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Erreur lors de la mise à jour du projet", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse("Erreur: " + e.getMessage()));
        }
    }

   // Supprime un projet et son fichier

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteProject(@PathVariable UUID id) {
        try {
            UUID userId = getCurrentUserId();
            log.info("Suppression du projet: {} par: {}", id, userId);

            projectService.deleteProject(id, userId);
            return ResponseEntity.ok(new SuccessResponse("Projet supprimé avec succès"));
        } catch (Exception e) {
            log.error("Erreur lors de la suppression du projet", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse("Erreur: " + e.getMessage()));
        }
    }

    // Ajouter un membre (testeur) à un projet

    @PostMapping("/{projectId}/members/{userId}")
    public ResponseEntity<?> addMember(
            @PathVariable UUID projectId,
            @PathVariable UUID userId) {
        try {
            UUID ownerId = getCurrentUserId();
            log.info("Ajout de membre au projet: {} par: {}", projectId, ownerId);

            projectService.addMember(projectId, userId, ownerId);
            return ResponseEntity.ok(new SuccessResponse("Membre ajouté avec succès"));
        } catch (Exception e) {
            log.error("Erreur lors de l'ajout de membre", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse("Erreur: " + e.getMessage()));
        }
    }
    // Générer les scripts de test IA pour un projet
    @PostMapping("/{id}/generate-tests")
    public ResponseEntity<?> generateTests(@PathVariable UUID id) {
        try {
            UUID userId = getCurrentUserId();
            log.info("Lancement de la génération des tests pour le projet: {} par: {}", id, userId);

            // Appel de la méthode de génération dans le service
            java.util.Map<String, Object> response = projectService.generateTestsForProject(id, userId);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Erreur lors de la génération des tests", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("Erreur: " + e.getMessage()));
        }
    }

    // Exécuter les tests générés
    @PostMapping("/{id}/run-tests")
    public ResponseEntity<?> runTests(@PathVariable UUID id, @org.springframework.web.bind.annotation.RequestBody(required = false) java.util.Map<String, Object> body) {
        try {
            UUID userId = getCurrentUserId();
            log.info("Lancement exécution des tests pour le projet: {} par: {}", id, userId);

            // Extract selected scenario IDs from request body if provided
            List<String> selectedScenarioIds = new ArrayList<>();
            if (body != null && body.containsKey("selectedScenarioIds")) {
                Object selectedIds = body.get("selectedScenarioIds");
                if (selectedIds instanceof List) {
                    selectedScenarioIds = (List<String>) selectedIds;
                }
            }

            java.util.Map<String, Object> response = projectService.runTests(id, userId, selectedScenarioIds);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Erreur lors de l'exécution des tests", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("Erreur d'exécution: " + e.getMessage()));
        }
    }



    @GetMapping("/{projectId}/execution-metrics")
    public ResponseEntity<?> getExecutionMetrics(
            @PathVariable UUID projectId) {
        try {
            UUID userId = getCurrentUserId();
            log.info("Récupération métriques d'exécution pour projet: {} par: {}", projectId, userId);

            Map<String, Object> metrics = projectService.getExecutionMetrics(projectId, userId);
            return ResponseEntity.ok(metrics);
        } catch (Exception e) {
            log.error("Erreur lors de la récupération des métriques", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("Erreur: " + e.getMessage()));
        }
    }

    @GetMapping("/{projectId}/execution-result/{resultId}/download-report")
    public ResponseEntity<?> downloadReport(
            @PathVariable UUID projectId,
            @PathVariable UUID resultId) {
        try {
            UUID userId = getCurrentUserId();
            log.info("Téléchargement rapport d'exécution {} pour projet: {} par: {}", resultId, projectId, userId);

            // Find the execution result
            java.util.Optional<com.example.backend.entity.TestExecutionResult> resultOpt =
                projectService.getTestExecutionResult(resultId);

            if (!resultOpt.isPresent()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse("Rapport non trouvé"));
            }

            com.example.backend.entity.TestExecutionResult result = resultOpt.get();

            // Verify user owns the project
            if (!result.getProject().getOwner().getId().equals(userId)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new ErrorResponse("Accès refusé"));
            }

            byte[] pdfContent = result.getReportPdfBlob();
            if (pdfContent == null || pdfContent.length == 0) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse("Aucun rapport PDF disponible"));
            }

            return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=test-report-" + resultId + ".pdf")
                .header("Content-Type", "application/pdf")
                .body(pdfContent);

        } catch (Exception e) {
            log.error("Erreur téléchargement rapport", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("Erreur: " + e.getMessage()));
        }
    }

    // Récupérer les scénarios persistés d'un projet
    @GetMapping("/{id}/scenarios")
    public ResponseEntity<List<java.util.Map<String, Object>>> getScenarios(@PathVariable UUID id) {
        try {
            UUID userId = getCurrentUserId();
            log.info("Récupération des scénarios pour le projet: {} par: {}", id, userId);

            List<java.util.Map<String, Object>> scenarios = projectService.getProjectScenarios(id, userId);
            return ResponseEntity.ok(scenarios);
        } catch (com.example.backend.exception.ResourceNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (Exception e) {
            log.error("Erreur lors de la récupération des scénarios", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Récupère le contenu du fichier uploadé (base64) pour les projets CODE_FICHIER
     */
    @GetMapping("/{id}/file-content")
    public ResponseEntity<?> getFileContent(@PathVariable UUID id) {
        try {
            UUID userId = getCurrentUserId();
            log.info("Récupération contenu fichier pour projet: {} par: {}", id, userId);

            Map<String, Object> fileData = projectService.getFileContent(id, userId);
            return ResponseEntity.ok(fileData);
        } catch (com.example.backend.exception.ResourceNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("Projet non trouvé"));
        } catch (com.example.backend.exception.BadRequestException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(e.getMessage()));
        } catch (Exception e) {
            log.error("Erreur lors de la récupération du fichier", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("Erreur: " + e.getMessage()));
        }
    }

   //Récupèrer l'ID de l'utilisateur connecté

    private UUID getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof com.example.backend.security.CustomUserDetails) {
            com.example.backend.security.CustomUserDetails userDetails = 
                    (com.example.backend.security.CustomUserDetails) authentication.getPrincipal();
            return userDetails.getUserId();
        }
        throw new RuntimeException("Utilisateur non authentifié correctement");
    }

    /**
     * Classe interne pour les réponses d'erreur
     */
    public static class ErrorResponse {
        public String message;

        public ErrorResponse(String message) {
            this.message = message;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }

    /**
     * Classe interne pour les réponses de succès
     */
    public static class SuccessResponse {
        public String message;

        public SuccessResponse(String message) {
            this.message = message;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }
}
