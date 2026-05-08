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
     * Build an enhanced prompt with user context
     */
    public String buildContextualPrompt(String userQuestion, String contextDataJson) {
        try {
            ObjectNode context = (ObjectNode) objectMapper.readTree(contextDataJson);

            StringBuilder prompt = new StringBuilder();
            prompt.append("You are a helpful testing automation assistant. ");
            prompt.append("You have knowledge about BDD, Gherkin, Selenium testing, and more.\n");

            String userName = context.get("userName") != null ? context.get("userName").asText() : "User";
            prompt.append("\nUser: ").append(userName).append("\n");

            Integer projectCount = context.get("totalProjects") != null ?
                context.get("totalProjects").asInt() : 0;
            if (projectCount > 0) {
                prompt.append("The user has ").append(projectCount).append(" projects.\n");
            }

            Integer testCount = context.get("totalTestScripts") != null ?
                context.get("totalTestScripts").asInt() : 0;
            if (testCount > 0) {
                prompt.append("They have ").append(testCount).append(" test scripts.\n");
            }

            prompt.append("\nQuestion type: ").append(
                context.get("questionType") != null ?
                    context.get("questionType").asText() : "general"
            ).append("\n");

            prompt.append("\nUser question: ").append(userQuestion).append("\n");
            prompt.append("\nPlease provide a helpful and concise answer in French if the user speaks French, otherwise in English.");

            return prompt.toString();

        } catch (Exception e) {
            log.error("Error building contextual prompt", e);
            return userQuestion;
        }
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
