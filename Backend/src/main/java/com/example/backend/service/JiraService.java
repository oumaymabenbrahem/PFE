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

import java.net.URI;
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

    @Autowired
    private UserRepository userRepository;

    private static final String DEFAULT_XRAY_BASE_URL = "https://xray.cloud.getxray.app";
    private static final List<String> XRAY_BASE_URL_FALLBACKS = List.of(
            DEFAULT_XRAY_BASE_URL,
            "https://eu.xray.cloud.getxray.app",
            "https://us.xray.cloud.getxray.app"
    );

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * Génère l'URL d'autorisation Atlassian
     */
    public String getAuthorizationUrl(UUID userId) {
        String scopes = "read:jira-user read:jira-work write:jira-work offline_access";
        return String.format(
                "https://auth.atlassian.com/authorize?audience=api.atlassian.com&client_id=%s&scope=%s&redirect_uri=%s&state=%s&response_type=code&prompt=consent",
                clientId, scopes.replace(" ", "%20"), redirectUri, userId.toString()
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

    private void refreshJiraToken(User user) {
        if (user.getJiraRefreshToken() == null || user.getJiraRefreshToken().isBlank()) {
            throw new RuntimeException("Session Jira expirée. Veuillez reconnecter votre compte Jira.");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, String> body = new java.util.HashMap<>();
        body.put("grant_type", "refresh_token");
        body.put("client_id", clientId);
        body.put("client_secret", clientSecret);
        body.put("refresh_token", user.getJiraRefreshToken());

        HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<Map<String, Object>> tokenResponse = restTemplate.exchange(
                    "https://auth.atlassian.com/oauth/token",
                    HttpMethod.POST,
                    request,
                    new ParameterizedTypeReference<Map<String, Object>>() {}
            );

            if (tokenResponse.getStatusCode() == HttpStatus.OK && tokenResponse.getBody() != null) {
                String accessToken = (String) tokenResponse.getBody().get("access_token");
                String refreshToken = (String) tokenResponse.getBody().get("refresh_token");

                if (accessToken == null || accessToken.isBlank()) {
                    throw new RuntimeException("Réponse Jira invalide: access_token manquant");
                }

                user.setJiraAccessToken(accessToken);
                if (refreshToken != null && !refreshToken.isBlank()) {
                    user.setJiraRefreshToken(refreshToken);
                }
                userRepository.save(user);
                log.info("Token Jira renouvelé pour l'utilisateur {}", user.getId());
                return;
            }

            throw new RuntimeException("Impossible de renouveler la session Jira");
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            log.error("Erreur refresh token Jira: {}", e.getResponseBodyAsString(), e);
            throw new RuntimeException("Session Jira expirée. Veuillez reconnecter votre compte Jira.", e);
        }
    }

    private ResponseEntity<Map<String, Object>> fetchAccessibleProjects(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(user.getJiraAccessToken());
        HttpEntity<String> entity = new HttpEntity<>("", headers);

        String url = "https://api.atlassian.com/ex/jira/" + user.getJiraCloudId()
                + "/rest/api/3/project/search?maxResults=100&orderBy=name";

        return restTemplate.exchange(
                url,
                HttpMethod.GET,
                entity,
                new ParameterizedTypeReference<Map<String, Object>>() {}
        );
    }

    private ResponseEntity<Map<String, Object>> fetchJiraProject(User user, String projectKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(user.getJiraAccessToken());
        HttpEntity<String> entity = new HttpEntity<>("", headers);

        String url = "https://api.atlassian.com/ex/jira/" + user.getJiraCloudId()
                + "/rest/api/3/project/" + projectKey.trim() + "?expand=issueTypes";

        return restTemplate.exchange(
                url,
                HttpMethod.GET,
                entity,
                new ParameterizedTypeReference<Map<String, Object>>() {}
        );
    }

    private void validateJiraProjectForXrayImport(User user, String projectKey) {
        ResponseEntity<Map<String, Object>> response;
        try {
            response = fetchJiraProject(user, projectKey);
        } catch (org.springframework.web.client.HttpClientErrorException.Unauthorized e) {
            refreshJiraToken(user);
            response = fetchJiraProject(user, projectKey);
        }

        Map<String, Object> project = response.getBody();
        Object issueTypes = project != null ? project.get("issueTypes") : null;
        boolean hasTestIssueType = false;

        if (issueTypes instanceof List<?> items) {
            for (Object item : items) {
                if (item instanceof Map<?, ?> issueType && issueType.get("name") != null
                        && "test".equalsIgnoreCase(issueType.get("name").toString())) {
                    hasTestIssueType = true;
                    break;
                }
            }
        }

        if (!hasTestIssueType) {
            throw new RuntimeException("Le projet Jira " + projectKey.trim()
                    + " ne contient pas le type d'issue Xray 'Test'. Activez/configurez Xray sur ce projet avant l'import.");
        }

        log.info("Projet Jira validé avant import Xray. ProjectKey={}, HasTestIssueType={}", projectKey.trim(), hasTestIssueType);
    }

    public List<Map<String, String>> getAccessibleProjects(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("Utilisateur non trouvé"));

        if (user.getJiraAccessToken() == null || user.getJiraCloudId() == null) {
            throw new RuntimeException("L'utilisateur n'est pas connecté à Jira.");
        }

        ResponseEntity<Map<String, Object>> response;
        try {
            response = fetchAccessibleProjects(user);
        } catch (org.springframework.web.client.HttpClientErrorException.Unauthorized e) {
            refreshJiraToken(user);
            response = fetchAccessibleProjects(user);
        }

        Object values = response.getBody() != null ? response.getBody().get("values") : null;
        List<Map<String, String>> projects = new java.util.ArrayList<>();

        if (values instanceof List<?> projectValues) {
            for (Object item : projectValues) {
                if (item instanceof Map<?, ?> project && project.get("key") != null) {
                    Map<String, String> mappedProject = new java.util.HashMap<>();
                    mappedProject.put("id", project.get("id") != null ? project.get("id").toString() : "");
                    mappedProject.put("key", project.get("key").toString());
                    mappedProject.put("name", project.get("name") != null ? project.get("name").toString() : project.get("key").toString());
                    projects.add(mappedProject);
                }
            }
        }

        return projects;
    }

    public boolean isUserConnected(UUID userId) {
        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            return user.getJiraAccessToken() != null && !user.getJiraAccessToken().isBlank()
                    && user.getJiraRefreshToken() != null && !user.getJiraRefreshToken().isBlank()
                    && user.getJiraCloudId() != null && !user.getJiraCloudId().isBlank();
        }
        return false;
    }

    public boolean isXrayConfigured(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("Utilisateur non trouvé"));

        return user.getXrayClientId() != null && !user.getXrayClientId().isBlank()
                && user.getXrayClientSecret() != null && !user.getXrayClientSecret().isBlank();
    }

    public String getXrayBaseUrl(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("Utilisateur non trouvé"));

        return normalizeXrayBaseUrl(user.getXrayBaseUrl());
    }

    public void saveXrayConfig(UUID userId, String clientId, String clientSecret, String baseUrl) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("Utilisateur non trouvé"));

        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            throw new RuntimeException("Client ID et Client Secret Xray sont obligatoires.");
        }

        String normalizedBaseUrl = normalizeXrayBaseUrl(baseUrl);
        authenticateXray(clientId.trim(), clientSecret.trim(), normalizedBaseUrl);

        user.setXrayClientId(clientId.trim());
        user.setXrayClientSecret(clientSecret.trim());
        user.setXrayBaseUrl(normalizedBaseUrl);
        userRepository.save(user);
        log.info("Configuration Xray enregistrée pour l'utilisateur {}", userId);
    }

    private String normalizeXrayBaseUrl(String baseUrl) {
        String normalizedBaseUrl = (baseUrl == null || baseUrl.isBlank())
                ? DEFAULT_XRAY_BASE_URL
                : baseUrl.trim();

        while (normalizedBaseUrl.endsWith("/")) {
            normalizedBaseUrl = normalizedBaseUrl.substring(0, normalizedBaseUrl.length() - 1);
        }

        try {
            URI uri = URI.create(normalizedBaseUrl);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                    || !(host.equals("xray.cloud.getxray.app") || host.endsWith(".xray.cloud.getxray.app"))) {
                throw new IllegalArgumentException();
            }
        } catch (Exception e) {
            throw new RuntimeException("URL Xray Cloud invalide.");
        }

        return normalizedBaseUrl;
    }

    private List<String> getXrayBaseUrlCandidates(String configuredBaseUrl) {
        List<String> candidates = new java.util.ArrayList<>();
        candidates.add(normalizeXrayBaseUrl(configuredBaseUrl));

        for (String fallback : XRAY_BASE_URL_FALLBACKS) {
            String normalizedFallback = normalizeXrayBaseUrl(fallback);
            if (!candidates.contains(normalizedFallback)) {
                candidates.add(normalizedFallback);
            }
        }

        return candidates;
    }

    /**
     * Authentification Xray System (Server-to-Server)
     */
    private String authenticateXray(String clientId, String clientSecret, String baseUrl) {
        if (clientId == null || clientId.isBlank()
                || clientSecret == null || clientSecret.isBlank()) {
            throw new RuntimeException("Veuillez configurer les identifiants Xray de votre compte.");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, String> body = new java.util.HashMap<>();
        body.put("client_id", clientId);
        body.put("client_secret", clientSecret);

        HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    normalizeXrayBaseUrl(baseUrl) + "/api/v2/authenticate", request, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                // Return string without quotes
                return response.getBody().replace("\"", "").trim();
            }
            throw new RuntimeException("Échec de l'authentification Xray");
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            String responseBody = e.getResponseBodyAsString();
            log.error("Erreur Xray Auth: {}", responseBody, e);
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                throw new RuntimeException("Client ID ou Client Secret Xray invalide. Créez une nouvelle clé API Xray et copiez le secret complet au moment de sa création.", e);
            }
            String xrayError = responseBody == null || responseBody.isBlank()
                    ? "HTTP " + e.getStatusCode()
                    : responseBody;
            throw new RuntimeException("Impossible de s'authentifier à Xray Cloud: " + xrayError, e);
        } catch (Exception e) {
            log.error("Erreur Inconnue Xray Auth: ", e);
            throw new RuntimeException("Impossible de contacter Xray Cloud", e);
        }
    }

    private ResponseEntity<String> importFeatureToXray(String baseUrl, String token, String projectKey, MultiValueMap<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);
        String importUrl = normalizeXrayBaseUrl(baseUrl) + "/api/v2/import/feature?projectKey=" + projectKey.trim();

        return restTemplate.exchange(importUrl, HttpMethod.POST, requestEntity, String.class);
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

        if (user.getXrayClientId() == null || user.getXrayClientId().isBlank()
                || user.getXrayClientSecret() == null || user.getXrayClientSecret().isBlank()) {
            throw new RuntimeException("Veuillez configurer vos identifiants Xray avant l'envoi.");
        }

        validateJiraProjectForXrayImport(user, projectKey);

        String xrayBaseUrl = normalizeXrayBaseUrl(user.getXrayBaseUrl());

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

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();

        // Enveloppe spécialisée Spring pour forcer le RestTemplate à envoyer un fichier avec le nom correct
        org.springframework.core.io.ByteArrayResource featureFile = new org.springframework.core.io.ByteArrayResource(gherkinContent.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "generated_tests.feature";
            }
        };

        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.TEXT_PLAIN);
        body.add("file", new HttpEntity<>(featureFile, fileHeaders));

        try {
            String xrayToken = authenticateXray(user.getXrayClientId(), user.getXrayClientSecret(), xrayBaseUrl);
            ResponseEntity<String> response = importFeatureToXray(xrayBaseUrl, xrayToken, projectKey, body);
            log.info("Succès Import Xray: {}", response.getBody());
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            String responseBody = e.getResponseBodyAsString();
            String xrayError = responseBody == null || responseBody.isBlank()
                    ? "HTTP " + e.getStatusCode()
                    : responseBody;
            log.error("Erreur API Xray: {} - {}", e.getStatusCode(), xrayError);

            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                for (String candidateBaseUrl : getXrayBaseUrlCandidates(xrayBaseUrl)) {
                    if (candidateBaseUrl.equals(xrayBaseUrl)) {
                        continue;
                    }

                    try {
                        log.info("Nouvelle tentative import Xray avec endpoint alternatif: {}", candidateBaseUrl);
                        String retryToken = authenticateXray(user.getXrayClientId(), user.getXrayClientSecret(), candidateBaseUrl);
                        ResponseEntity<String> retryResponse = importFeatureToXray(candidateBaseUrl, retryToken, projectKey, body);
                        user.setXrayBaseUrl(candidateBaseUrl);
                        userRepository.save(user);
                        log.info("Succès Import Xray avec endpoint {}: {}", candidateBaseUrl, retryResponse.getBody());
                        return;
                    } catch (org.springframework.web.client.HttpStatusCodeException retryException) {
                        String retryBody = retryException.getResponseBodyAsString();
                        String retryError = retryBody == null || retryBody.isBlank()
                                ? "HTTP " + retryException.getStatusCode()
                                : retryBody;
                        log.warn("Échec endpoint Xray alternatif {}: {} - {}",
                                candidateBaseUrl, retryException.getStatusCode(), retryError);
                    } catch (Exception retryException) {
                        log.warn("Échec endpoint Xray alternatif {}: {}", candidateBaseUrl, retryException.getMessage());
                    }
                }

                throw new RuntimeException("Xray refuse l'import (401). Le compte Jira connecté voit le projet "
                        + projectKey.trim() + " et son type 'Test', donc vérifiez surtout l'utilisateur lié à la clé API Xray: il doit être dans le même site Jira/Xray et avoir les droits Xray/Jira d'import et de création de Tests.", e);
            }

            throw new RuntimeException("Erreur détaillée Xray: " + xrayError, e);
        } catch (Exception e) {
            log.error("Erreur Critique Connexion Xray: ", e);
            throw new RuntimeException("Échec total lors de l'envoi vers Xray Cloud");
        }
    }
}
