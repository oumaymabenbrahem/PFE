package com.example.backend.service;

import com.example.backend.entity.User;
import com.example.backend.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
public class JiraService {

    @Value("${jira.client-id}")
    private String clientId;

    @Value("${jira.client-secret}")
    private String clientSecret;

    @Value("${jira.redirect-uri}")
    private String redirectUri;

    @Value("${xray.client-id}")
    private String xrayClientId;

    @Value("${xray.client-secret}")
    private String xrayClientSecret;

    @Autowired
    private UserRepository userRepository;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * Génère l'URL d'autorisation Atlassian
     */
    public String getAuthorizationUrl(UUID userId) {
        String scopes = "read:jira-user read:jira-work write:jira-work";
        return String.format(
                "https://auth.atlassian.com/authorize?audience=api.atlassian.com&client_id=%s&scope=%s&redirect_uri=%s&state=%s&response_type=code&prompt=consent",
                clientId, scopes, redirectUri, userId.toString()
        );
    }

    /**
     * Gère le callback de Jira : échange le code contre un token, récupère le cloudId, et enregistre dans la DB.
     */
    public void handleCallback(String code, String state) {
        try {
            UUID userId = UUID.fromString(state);
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new RuntimeException("Utilisateur non trouvé"));

            // 1. Échanger le code contre un token
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, String> body = new java.util.HashMap<>();
            body.put("grant_type", "authorization_code");
            body.put("client_id", clientId);
            body.put("client_secret", clientSecret);
            body.put("code", code);
            body.put("redirect_uri", redirectUri);

            HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);
            
            ResponseEntity<Map<String, Object>> tokenResponse = restTemplate.exchange(
                    "https://auth.atlassian.com/oauth/token", 
                    HttpMethod.POST, 
                    request, 
                    new ParameterizedTypeReference<Map<String, Object>>() {});

            if (tokenResponse.getStatusCode() == HttpStatus.OK && tokenResponse.getBody() != null) {
                String accessToken = (String) tokenResponse.getBody().get("access_token");
                String refreshToken = (String) tokenResponse.getBody().get("refresh_token");

                user.setJiraAccessToken(accessToken);
                user.setJiraRefreshToken(refreshToken);

                // 2. Récupérer le cloudId
                String cloudId = fetchCloudId(accessToken);
                user.setJiraCloudId(cloudId);

                userRepository.save(user);
                log.info("Connexion Jira réussie pour l'utilisateur {}", userId);
            } else {
                throw new RuntimeException("Erreur de récupération du token Jira");
            }
        } catch (Exception e) {
            log.error("Erreur lors du traitement du callback Jira: ", e);
            throw new RuntimeException("Echec de l'intégration Jira", e);
        }
    }

    private String fetchCloudId(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<String> entity = new HttpEntity<>("", headers);

        ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                "https://api.atlassian.com/oauth/token/accessible-resources",
                HttpMethod.GET,
                entity,
                new ParameterizedTypeReference<List<Map<String, Object>>>() {}
        );

        if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null && !response.getBody().isEmpty()) {
            return (String) response.getBody().get(0).get("id");
        }
        throw new RuntimeException("Impossible de trouver le Jira Cloud ID");
    }

    public boolean isUserConnected(UUID userId) {
        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            return user.getJiraAccessToken() != null && !user.getJiraAccessToken().isEmpty();
        }
        return false;
    }

    /**
     * Authentification Xray System (Server-to-Server)
     */
    private String authenticateXray() {
        if (xrayClientId == null || xrayClientSecret == null || xrayClientId.contains("votre_xray")) {
            throw new RuntimeException("Veuillez configurer xray.client-id et xray.client-secret dans application.properties");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, String> body = new java.util.HashMap<>();
        body.put("client_id", xrayClientId);
        body.put("client_secret", xrayClientSecret);

        HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    "https://xray.cloud.getxray.app/api/v2/authenticate", request, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                // Return string without quotes
                return response.getBody().replace("\"", "");
            }
            throw new RuntimeException("Échec de l'authentification Xray");
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            log.error("Erreur Xray Auth: {}", e.getResponseBodyAsString(), e);
            throw new RuntimeException("Impossible de s'authentifier à Xray Cloud (Identifiants invalides)");
        } catch (Exception e) {
            log.error("Erreur Inconnue Xray Auth: ", e);
            throw new RuntimeException("Impossible de contacter Xray Cloud", e);
        }
    }

    /**
     * Push les scénarios Gherkin vers Xray Cloud via Multipart.
     */
    public void pushTestsToXray(UUID userId, String projectKey, String userStoryId, List<Map<String, Object>> scenarios) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("Utilisateur non trouvé"));

        if (user.getJiraAccessToken() == null || user.getJiraCloudId() == null) {
            throw new RuntimeException("L'utilisateur n'est pas connecté à Jira.");
        }

        if (projectKey == null || projectKey.trim().isEmpty()) {
            throw new RuntimeException("La Project Key Jira est obligatoire pour Xray.");
        }

        String xrayToken = authenticateXray();

        StringBuilder gherkinContent = new StringBuilder();

        // Ajout du Tag Jira (Liaison Automatique avec la User Story / Epic)
        if (userStoryId != null && !userStoryId.trim().isEmpty()) {
            gherkinContent.append("@").append(userStoryId.trim()).append("\n");
        }

        gherkinContent.append("Feature: Scénarios générés automatiquement pour le projet ").append(projectKey.trim()).append("\n\n");

        for (Map<String, Object> scenario : scenarios) {
            if (scenario.containsKey("senario")) {
                String rawScenario = (String) scenario.get("senario");
                // Remove repeated "Feature:" lines generated by AI
                String cleanScenario = rawScenario.replaceAll("(?im)^\\s*Feature:.*$", "").trim();
                gherkinContent.append("\n").append(cleanScenario).append("\n");
            }
        }

        log.info("--- CONTENU GHERKIN FINAL --- \n{}\n--------------------------", gherkinContent.toString());

        log.info("Poussée des tests Gherkin vers Jira/Xray. ProjectKey={}, UserStory={}, ContentLength={}",
                projectKey, userStoryId, gherkinContent.length());

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(xrayToken);
        // IMPORTANT: Ne pas forcer le Content-Type ici, Spring va s'en charger et ajouter le "boundary=..." requis par Xray !

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();

        // Enveloppe spécialisée Spring pour forcer le RestTemplate à envoyer un fichier avec le nom correct
        org.springframework.core.io.ByteArrayResource featureFile = new org.springframework.core.io.ByteArrayResource(gherkinContent.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "generated_tests.feature";
            }
        };

        body.add("file", featureFile);

        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        String importUrl = "https://xray.cloud.getxray.app/api/v2/import/feature?projectKey=" + projectKey.trim();

        try {
            ResponseEntity<String> response = restTemplate.exchange(importUrl, HttpMethod.POST, requestEntity, String.class);
            log.info("Succès Import Xray: {}", response.getBody());
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            log.error("Erreur API Xray: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("Erreur détaillée Xray: " + e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("Erreur Critique Connexion Xray: ", e);
            throw new RuntimeException("Échec total lors de l'envoi vers Xray Cloud");
        }
    }
}
