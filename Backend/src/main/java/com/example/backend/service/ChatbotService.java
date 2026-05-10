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

    private static final int MAX_HISTORY_MESSAGES = 10;

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

            // Build system prompt with context
            String systemPrompt = userContextService.buildSystemPrompt(contextData);

            // Fetch recent conversation history for context
            List<HuggingFaceClient.ChatMessage> conversationHistory = getRecentConversationHistory(userId);

            // Call Hugging Face API with full context
            String botResponse;
            try {
                if (!huggingFaceClient.isAvailable()) {
                    log.info("Hugging Face API not available, using fallback response");
                    botResponse = generateFallbackResponse(userMessage, contextData);
                } else {
                    try {
                        botResponse = huggingFaceClient.generateResponse(systemPrompt, conversationHistory, userMessage);
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
     * Get recent conversation history for context
     */
    private List<HuggingFaceClient.ChatMessage> getRecentConversationHistory(UUID userId) {
        try {
            List<ChatbotMessage> recentMessages = chatbotMessageRepository
                .findTop50ByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .limit(MAX_HISTORY_MESSAGES)
                .toList();

            // Reverse to get chronological order (oldest first)
            List<HuggingFaceClient.ChatMessage> history = new ArrayList<>();
            for (int i = recentMessages.size() - 1; i >= 0; i--) {
                ChatbotMessage msg = recentMessages.get(i);
                history.add(new HuggingFaceClient.ChatMessage(msg.getUserMessage(), msg.getBotResponse()));
            }

            return history;
        } catch (Exception e) {
            log.warn("Could not fetch conversation history", e);
            return List.of();
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
     * Provides contextual responses based on question type and user context
     */
    private String generateFallbackResponse(String userMessage, String contextData) {
        String lowerMessage = userMessage.toLowerCase();

        // Build context info if available
        String contextInfo = "";
        if (contextData != null && !contextData.equals("{}")) {
            try {
                var contextNode = objectMapper.readTree(contextData);
                String userName = contextNode.has("userName") ? contextNode.get("userName").asText() : null;
                int projectCount = contextNode.has("totalProjects") ? contextNode.get("totalProjects").asInt() : 0;
                if (userName != null && projectCount > 0) {
                    contextInfo = " " + userName + ", vous avez " + projectCount + " projet(s) sur la plateforme.";
                }
            } catch (Exception e) {
                // Ignore parsing errors in fallback
            }
        }

        // Contextual responses for common testing topics
        if (lowerMessage.contains("gherkin") || lowerMessage.contains("bdd")) {
            return "Gherkin est un langage BDD (Behavior-Driven Development) utilisé pour écrire des scénarios de test lisibles. " +
                "Il utilise les mots-clés 'Soit' (Given), 'Quand' (When), 'Alors' (Then). Sur TEST2i, ces scénarios sont automatiquement " +
                "transformés en scripts Selenium." + contextInfo;
        }

        if (lowerMessage.contains("projet") || lowerMessage.contains("project")) {
            return "Un projet TEST2i regroupe vos scénarios de test BDD. Vous pouvez créer un projet à partir d'une user story, " +
                "d'un lien GitHub, d'un fichier de code ou d'une URL d'application web." + contextInfo;
        }

        if (lowerMessage.contains("test") || lowerMessage.contains("scénario")) {
            return "Les tests sur TEST2i sont des scénarios BDD en langage Gherkin. L'IA analyse votre application et génère " +
                "automatiquement des scénarios de test, puis les exécute via Selenium." + contextInfo;
        }

        if (lowerMessage.contains("exécuter") || lowerMessage.contains("run") || lowerMessage.contains("lancer")) {
            return "Pour exécuter vos tests : sélectionnez un projet, choisissez les scénarios, puis cliquez sur 'Exécuter'. " +
                "Selenium ouvrira un navigateur Chrome et exécutera les tests automatiquement." + contextInfo;
        }

        if (lowerMessage.contains("selenium")) {
            return "Selenium est un outil d'automatisation de navigateur web. TEST2i l'utilise pour exécuter vos scénarios " +
                "Gherkin de manière automatisée, avec captures d'écran et rapports détaillés." + contextInfo;
        }

        if (lowerMessage.contains("rapport") || lowerMessage.contains("report")) {
            return "Après l'exécution des tests, TEST2i génère un rapport détaillé avec les résultats, les captures d'écran " +
                "de chaque étape, et l'export PDF/Jira Xray." + contextInfo;
        }

        if (lowerMessage.contains("jira") || lowerMessage.contains("xray")) {
            return "TEST2i s'intègre avec Jira et Xray pour exporter vos résultats de test directement dans vos tickets. " +
                "Connectez votre compte Jira depuis les paramètres du profil." + contextInfo;
        }

        // Greeting
        if (lowerMessage.contains("bonjour") || lowerMessage.contains("salut") || lowerMessage.contains("hello") || lowerMessage.contains("hi")) {
            return "Bonjour ! 👋 Je suis l'assistant IA de TEST2i. Je peux vous aider avec les tests d'automatisation, " +
                "Gherkin, Selenium, vos projets, ou répondre à toute autre question. Comment puis-je vous aider ?" + contextInfo;
        }

        // Thank you
        if (lowerMessage.contains("merci") || lowerMessage.contains("thank")) {
            return "De rien ! 😊 N'hésitez pas si vous avez d'autres questions, que ce soit sur TEST2i ou sur tout autre sujet." + contextInfo;
        }

        // Dynamic generic response - acknowledge the question and offer help
        return "Merci pour votre question. Je suis actuellement en mode dégradé (l'IA n'est pas disponible temporairement), " +
            "mais je peux tout de même vous aider. Sur TEST2i, je peux répondre aux questions sur les tests BDD, Gherkin, " +
            "Selenium, vos projets et plus encore. Pour des réponses plus détaillées et dynamiques, réessayez dans quelques instants." + contextInfo;
    }
}
