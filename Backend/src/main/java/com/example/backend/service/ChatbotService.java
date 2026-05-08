package com.example.backend.service;

import com.example.backend.dto.ChatbotMessageRequest;
import com.example.backend.dto.ChatbotMessageResponse;
import com.example.backend.entity.ChatbotMessage;
import com.example.backend.repository.ChatbotMessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Main chatbot service orchestrating:
 * 1. User context extraction
 * 2. Hugging Face API calls
 * 3. Message persistence
 */
@Service
@Slf4j
public class ChatbotService {

    @Autowired
    private HuggingFaceClient huggingFaceClient;

    @Autowired
    private UserContextService userContextService;

    @Autowired
    private ChatbotMessageRepository chatbotMessageRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Process user message and generate AI response
     */
    public ChatbotMessageResponse processMessage(UUID userId, ChatbotMessageRequest request) {
        try {
            log.info("Processing message for user: {}", userId);

            String userMessage = request.getUserMessage().trim();
            boolean includeContext = request.isIncludeContext();

            // Validate input
            if (userMessage.isEmpty()) {
                return buildErrorResponse("Le message ne peut pas être vide.");
            }

            if (userMessage.length() > 1000) {
                return buildErrorResponse("Le message est trop long (max 1000 caractères).");
            }

            // Extract context if requested
            String contextData = null;
            String messageType = "GENERAL";

            if (includeContext) {
                contextData = userContextService.extractUserContext(userId, userMessage);
                try {
                    var contextNode = objectMapper.readTree(contextData);
                    if (contextNode.get("questionType") != null) {
                        messageType = contextNode.get("questionType").asText();
                    }
                } catch (Exception e) {
                    log.debug("Could not parse context data", e);
                }
            }

            // Build prompt with context
            String prompt = userMessage;
            if (includeContext && contextData != null) {
                prompt = userContextService.buildContextualPrompt(userMessage, contextData);
            }

            // Call Hugging Face API
            String botResponse;
            try {
                if (!huggingFaceClient.isAvailable()) {
                    // API not available, skip to fallback directly
                    log.info("Hugging Face API not available, using fallback response");
                    botResponse = generateFallbackResponse(userMessage, contextData);
                } else {
                    try {
                        botResponse = huggingFaceClient.generateResponse(prompt);
                    } catch (Exception hfError) {
                        log.warn("Hugging Face API call failed, using fallback response", hfError);
                        botResponse = generateFallbackResponse(userMessage, contextData);
                    }
                }
            } catch (Exception e) {
                log.error("Error generating response", e);
                botResponse = "Je n'ai pas pu générer une réponse. Veuillez réessayer.";
                messageType = "ERROR";
            }

            // Save message to database
            ChatbotMessage savedMessage = saveChatMessage(userId, userMessage, botResponse,
                messageType, contextData);

            // Return response
            return ChatbotMessageResponse.builder()
                .id(savedMessage.getId())
                .userMessage(userMessage)
                .botResponse(botResponse)
                .messageType(messageType)
                .contextData(contextData)
                .createdAt(savedMessage.getCreatedAt())
                .build();

        } catch (Exception e) {
            log.error("Error processing message", e);
            return buildErrorResponse("Une erreur est survenue. Veuillez réessayer.");
        }
    }

    /**
     * Get conversation history for a user
     */
    public List<ChatbotMessageResponse> getConversationHistory(UUID userId, int limit) {
        try {
            List<ChatbotMessage> messages;

            if (limit > 0) {
                messages = chatbotMessageRepository.findTop50ByUserIdOrderByCreatedAtDesc(userId);
                // Limit further if needed
                messages = messages.stream()
                    .limit(limit)
                    .toList();
            } else {
                messages = chatbotMessageRepository.findByUserIdOrderByCreatedAtDesc(userId);
            }

            // Convert to response DTOs
            List<ChatbotMessageResponse> responses = new ArrayList<>();
            for (ChatbotMessage msg : messages) {
                responses.add(ChatbotMessageResponse.builder()
                    .id(msg.getId())
                    .userMessage(msg.getUserMessage())
                    .botResponse(msg.getBotResponse())
                    .messageType(msg.getMessageType())
                    .contextData(msg.getContextData())
                    .createdAt(msg.getCreatedAt())
                    .build());
            }

            return responses;

        } catch (Exception e) {
            log.error("Error fetching conversation history", e);
            return new ArrayList<>();
        }
    }

    /**
     * Delete a specific message
     */
    @Transactional
    public boolean deleteMessage(UUID messageId, UUID userId) {
        try {
            ChatbotMessage message = chatbotMessageRepository.findById(messageId).orElse(null);

            if (message == null) {
                log.warn("Message not found: {}", messageId);
                return false;
            }

            if (!message.getUserId().equals(userId)) {
                log.warn("User {} attempted to delete message of user {}", userId, message.getUserId());
                return false;
            }

            chatbotMessageRepository.deleteById(messageId);
            log.info("Message deleted: {}", messageId);
            return true;

        } catch (Exception e) {
            log.error("Error deleting message", e);
            return false;
        }
    }

    /**
     * Clear entire conversation for a user
     */
    @Transactional
    public void clearConversationHistory(UUID userId) {
        try {
            chatbotMessageRepository.deleteByUserId(userId);
            log.info("Conversation history cleared for user: {}", userId);
        } catch (Exception e) {
            log.error("Error clearing conversation history", e);
        }
    }

    /**
     * Save chat message to database
     */
    private ChatbotMessage saveChatMessage(UUID userId, String userMessage, String botResponse,
                                          String messageType, String contextData) {
        try {
            ChatbotMessage message = ChatbotMessage.builder()
                .userId(userId)
                .userMessage(userMessage)
                .botResponse(botResponse)
                .messageType(messageType)
                .contextData(contextData)
                .build();

            return chatbotMessageRepository.save(message);

        } catch (Exception e) {
            log.error("Error saving chat message", e);
            throw new RuntimeException("Failed to save message", e);
        }
    }

    /**
     * Build error response
     */
    private ChatbotMessageResponse buildErrorResponse(String errorMessage) {
        return ChatbotMessageResponse.builder()
            .botResponse(errorMessage)
            .messageType("ERROR")
            .build();
    }

    /**
     * Generate fallback response when HF API fails
     * Provides intelligent responses based on keywords
     */
    private String generateFallbackResponse(String userMessage, String contextData) {
        String lowerMessage = userMessage.toLowerCase();

        if (lowerMessage.contains("gherkin") || lowerMessage.contains("bdd")) {
            return "Gherkin is a Business-Driven Development (BDD) language used to write test scenarios in a human-readable format. " +
                "It uses keywords like 'Given', 'When', 'Then' to describe test steps. Gherkin scenarios can be executed by automation tools like Selenium.";
        }

        if (lowerMessage.contains("projet") || lowerMessage.contains("project")) {
            return "Un projet dans cette application est une collection de scénarios de test BDD. Vous pouvez créer des projets, y ajouter des tests, " +
                "et exécuter l'automatisation pour valider votre application.";
        }

        if (lowerMessage.contains("test") || lowerMessage.contains("scénario")) {
            return "Les tests dans cette application sont des scénarios BDD écrits en langage Gherkin. Chaque scénario teste un comportement spécifique " +
                "de votre application via Selenium automation.";
        }

        if (lowerMessage.contains("exécuter") || lowerMessage.contains("run") || lowerMessage.contains("lancer")) {
            return "Pour exécuter vos tests, sélectionnez un projet, choisissez les scénarios à exécuter, puis cliquez sur 'Exécuter'. " +
                "Les tests s'exécuteront via Selenium dans un navigateur Chrome.";
        }

        if (lowerMessage.contains("selenium")) {
            return "Selenium est un outil d'automatisation des tests qui contrôle un navigateur web. Dans cette application, Selenium exécute " +
                "vos scénarios Gherkin pour automatiser les tests de votre application.";
        }

        if (lowerMessage.contains("rapport") || lowerMessage.contains("report")) {
            return "Après l'exécution des tests, vous recevez un rapport détaillé avec le résumé des résultats, des captures d'écran de chaque étape, " +
                "et la possibilité de télécharger le rapport en PDF.";
        }

        if (lowerMessage.contains("erreur") || lowerMessage.contains("error") || lowerMessage.contains("fail")) {
            return "Si vos tests échouent, vérifiez les logs d'exécution pour voir où le problème s'est produit. " +
                "Consultez les captures d'écran associées pour mieux comprendre l'erreur.";
        }

        // Generic response
        return "Je suis un assistant pour l'automatisation des tests BDD avec Gherkin et Selenium. " +
            "Je peux répondre à vos questions sur les projets, les tests, et l'exécution. Posez une question spécifique comme: " +
            "'Comment écrire un scénario Gherkin?' ou 'Comment exécuter les tests?'";
    }
}
