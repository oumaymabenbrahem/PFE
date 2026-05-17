package com.example.backend.controller;

import com.example.backend.service.JiraService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.view.RedirectView;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/jira")
@CrossOrigin(origins = {"http://localhost:4200", "http://localhost:3000"})
@Slf4j
public class JiraController {

    @Autowired
    private JiraService jiraService;

    @GetMapping("/login")
    public ResponseEntity<?> getJiraLoginUrl() {
        try {
            UUID userId = getCurrentUserId();
            String url = jiraService.getAuthorizationUrl(userId);
            Map<String, String> response = new HashMap<>();
            response.put("url", url);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Erreur lors de la création de l'URL Jira", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ProjectController.ErrorResponse("Erreur interne"));
        }
    }

    @GetMapping("/callback")
    public RedirectView handleJiraCallback(
            @RequestParam("code") String code,
            @RequestParam("state") String state) {
        try {
            jiraService.handleCallback(code, state);
            return new RedirectView("http://localhost:4200/dashboard/projects-list?jira=success");
        } catch (Exception e) {
            log.error("Erreur lors du callback Jira", e);
            return new RedirectView("http://localhost:4200/dashboard/projects-list?jira=error");
        }
    }

    @GetMapping("/status")
    public ResponseEntity<?> getJiraStatus() {
        try {
            UUID userId = getCurrentUserId();
            boolean isConnected = jiraService.isUserConnected(userId);
            Map<String, Boolean> response = new HashMap<>();
            response.put("connected", isConnected);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
    }

    @GetMapping("/projects")
    public ResponseEntity<?> getJiraProjects() {
        try {
            UUID userId = getCurrentUserId();
            return ResponseEntity.ok(jiraService.getAccessibleProjects(userId));
        } catch (Exception e) {
            log.error("Erreur lors de la récupération des projets Jira", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ProjectController.ErrorResponse("Erreur récupération projets Jira: " + e.getMessage()));
        }
    }

    @GetMapping("/xray-config/status")
    public ResponseEntity<?> getXrayConfigStatus() {
        try {
            UUID userId = getCurrentUserId();
            Map<String, Object> response = new HashMap<>();
            response.put("configured", jiraService.isXrayConfigured(userId));
            response.put("baseUrl", jiraService.getXrayBaseUrl(userId));
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Erreur lors de la vérification de la configuration Xray", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ProjectController.ErrorResponse("Erreur configuration Xray: " + e.getMessage()));
        }
    }

    @PostMapping("/xray-config")
    public ResponseEntity<?> saveXrayConfig(@RequestBody Map<String, Object> payload) {
        try {
            UUID userId = getCurrentUserId();
            String clientId = (String) payload.get("clientId");
            String clientSecret = (String) payload.get("clientSecret");
            String baseUrl = (String) payload.get("baseUrl");

            jiraService.saveXrayConfig(userId, clientId, clientSecret, baseUrl);

            return ResponseEntity.ok(new ProjectController.SuccessResponse("Configuration Xray enregistrée avec succès."));
        } catch (Exception e) {
            log.error("Erreur lors de l'enregistrement de la configuration Xray", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ProjectController.ErrorResponse("Erreur configuration Xray: " + e.getMessage()));
        }
    }

    @PostMapping("/push-tests")
    public ResponseEntity<?> pushTestsToXray(@RequestBody Map<String, Object> payload) {
        try {
            UUID userId = getCurrentUserId();
            String projectKey = (String) payload.get("projectKey");
            String userStoryId = (String) payload.get("userStoryId");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> scenarios = (List<Map<String, Object>>) payload.get("scenarios");

            jiraService.pushTestsToXray(userId, projectKey, userStoryId, scenarios);

            return ResponseEntity.ok(new ProjectController.SuccessResponse("Tests poussés vers Jira/Xray avec succès."));
        } catch (Exception e) {
            log.error("Erreur lors du push Jira", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ProjectController.ErrorResponse("Erreur push Jira: " + e.getMessage()));
        }
    }

    private UUID getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof com.example.backend.security.CustomUserDetails) {
            com.example.backend.security.CustomUserDetails userDetails =
                    (com.example.backend.security.CustomUserDetails) authentication.getPrincipal();
            return userDetails.getUserId();
        }
        throw new RuntimeException("Utilisateur non authentifié");
    }
}
