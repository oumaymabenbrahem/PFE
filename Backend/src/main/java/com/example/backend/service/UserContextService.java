package com.example.backend.service;

import com.example.backend.entity.Project;
import com.example.backend.entity.User;
import com.example.backend.repository.ProjectRepository;
import com.example.backend.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Service for extracting user context data for AI responses
 */
@Service
@Slf4j
public class UserContextService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Extract user context for AI responses
     */
    public String extractUserContext(UUID userId, String question) {
        try {
            ObjectNode contextNode = objectMapper.createObjectNode();

            // Get user info
            User user = userRepository.findById(userId).orElse(null);
            if (user != null) {
                contextNode.put("userName", user.getNom());
                contextNode.put("userEmail", user.getEmail());
            }

            // Get user projects
            List<Project> projects = projectRepository.findByOwnerId(userId);
            contextNode.put("totalProjects", projects.size());
            contextNode.put("projectNames", projects.stream()
                .map(Project::getNom)
                .toList()
                .toString());

            String questionLower = question.toLowerCase();

            if (isProjectRelatedQuestion(questionLower)) {
                if (!projects.isEmpty()) {
                    contextNode.put("recentProject", projects.get(0).getNom());
                    contextNode.put("recentProjectStatus", projects.get(0).getStatut().toString());
                }
            }

            if (isTestRelatedQuestion(questionLower)) {
                long totalTests = projects.stream()
                    .mapToLong(p -> p.getTestScripts() != null ? p.getTestScripts().size() : 0)
                    .sum();
                contextNode.put("totalTestScripts", totalTests);
            }

            contextNode.put("questionType", categorizeQuestion(questionLower));

            return objectMapper.writeValueAsString(contextNode);

        } catch (Exception e) {
            log.error("Error extracting user context", e);
            return "{}";
        }
    }

    /**
     * Build a system prompt for the chat completions API with user context
     */
    public String buildSystemPrompt(String contextDataJson) {
        StringBuilder systemPrompt = new StringBuilder();

        systemPrompt.append("Tu es un assistant IA intelligent et polyvalent intégré dans une plateforme de test d'automatisation (TEST2i). ");
        systemPrompt.append("Tu peux répondre à TOUTES les questions de l'utilisateur, pas seulement celles liées aux tests.\n\n");

        systemPrompt.append("Règles :\n");
        systemPrompt.append("- Réponds en français si l'utilisateur écrit en français, sinon dans sa langue.\n");
        systemPrompt.append("- Sois concis, utile et précis.\n");
        systemPrompt.append("- Si la question concerne les tests, Gherkin, Selenium, BDD ou les projets, utilise ton expertise.\n");
        systemPrompt.append("- Si la question est générale (programmation, technologie, aide, etc.), réponds de ton mieux.\n");
        systemPrompt.append("- Ne dis jamais que tu ne peux répondre qu'aux questions de test.\n\n");

        // Add user context if available
        if (contextDataJson != null && !contextDataJson.isEmpty() && !contextDataJson.equals("{}")) {
            try {
                ObjectNode context = (ObjectNode) objectMapper.readTree(contextDataJson);

                String userName = context.get("userName") != null ? context.get("userName").asText() : null;
                if (userName != null) {
                    systemPrompt.append("Contexte utilisateur :\n");
                    systemPrompt.append("- Nom : ").append(userName).append("\n");
                }

                int projectCount = context.get("totalProjects") != null ? context.get("totalProjects").asInt() : 0;
                if (projectCount > 0) {
                    systemPrompt.append("- Projets : ").append(projectCount);
                    if (context.get("projectNames") != null) {
                        systemPrompt.append(" (").append(context.get("projectNames").asText()).append(")");
                    }
                    systemPrompt.append("\n");
                }

                int testCount = context.get("totalTestScripts") != null ? context.get("totalTestScripts").asInt() : 0;
                if (testCount > 0) {
                    systemPrompt.append("- Scripts de test : ").append(testCount).append("\n");
                }

                String recentProject = context.get("recentProject") != null ? context.get("recentProject").asText() : null;
                if (recentProject != null) {
                    systemPrompt.append("- Projet récent : ").append(recentProject);
                    if (context.get("recentProjectStatus") != null) {
                        systemPrompt.append(" (").append(context.get("recentProjectStatus").asText()).append(")");
                    }
                    systemPrompt.append("\n");
                }

                String questionType = context.get("questionType") != null ? context.get("questionType").asText() : null;
                if (questionType != null) {
                    systemPrompt.append("- Type de question : ").append(questionType).append("\n");
                }

            } catch (Exception e) {
                log.debug("Could not parse context data for system prompt", e);
            }
        }

        systemPrompt.append("\nPlateforme TEST2i : Création de projets de test, génération de scénarios Gherkin via IA, ");
        systemPrompt.append("exécution Selenium automatisée, rapports détaillés, intégration Jira/Xray.");

        return systemPrompt.toString();
    }

    /**
     * Build an enhanced prompt with user context (legacy method)
     */
    public String buildContextualPrompt(String userQuestion, String contextDataJson) {
        return buildSystemPrompt(contextDataJson);
    }

    private String categorizeQuestion(String questionLower) {
        if (isProjectRelatedQuestion(questionLower)) return "PROJECT_RELATED";
        if (isTestRelatedQuestion(questionLower)) return "TEST_RELATED";
        if (isGherkinRelatedQuestion(questionLower)) return "GHERKIN_RELATED";
        return "GENERAL";
    }

    private boolean isProjectRelatedQuestion(String question) {
        return question.contains("projet") || question.contains("project") ||
               question.contains("mon") || question.contains("my");
    }

    private boolean isTestRelatedQuestion(String question) {
        return question.contains("test") || question.contains("exécution") ||
               question.contains("execution") || question.contains("résultat") ||
               question.contains("result");
    }

    private boolean isGherkinRelatedQuestion(String question) {
        return question.contains("gherkin") || question.contains("behave") ||
               question.contains("scenario") || question.contains("step");
    }
}
