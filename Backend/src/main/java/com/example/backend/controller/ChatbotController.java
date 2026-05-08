package com.example.backend.controller;

import com.example.backend.dto.ChatbotMessageRequest;
import com.example.backend.dto.ChatbotMessageResponse;
import com.example.backend.entity.User;
import com.example.backend.repository.UserRepository;
import com.example.backend.service.ChatbotService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST Controller for Chatbot endpoints
 */
@RestController
@RequestMapping("/api/chatbot")
@CrossOrigin(origins = {"http://localhost:4200", "http://localhost:3000"})
@Slf4j
public class ChatbotController {

    @Autowired
    private ChatbotService chatbotService;

    @Autowired
    private UserRepository userRepository;

    /**
     * POST /api/chatbot/message
     * Process user message and get AI response
     */
    @PostMapping("/message")
    public ResponseEntity<?> sendMessage(@Valid @RequestBody ChatbotMessageRequest request) {
        try {
            UUID userId = getCurrentUserId();
            log.info("Chat message received from user: {}", userId);

            ChatbotMessageResponse response = chatbotService.processMessage(userId, request);

            if ("ERROR".equals(response.getMessageType()) && response.getId() == null) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error processing chat message", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("Erreur lors du traitement du message"));
        }
    }

    /**
     * GET /api/chatbot/history
     * Get conversation history for current user
     * Optional: ?limit=20 to limit results
     */
    @GetMapping("/history")
    public ResponseEntity<?> getHistory(@RequestParam(defaultValue = "0") int limit) {
        try {
            UUID userId = getCurrentUserId();
            log.info("Fetching chat history for user: {}", userId);

            List<ChatbotMessageResponse> history = chatbotService.getConversationHistory(userId, limit);

            Map<String, Object> response = new HashMap<>();
            response.put("messages", history);
            response.put("count", history.size());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error fetching chat history", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("Erreur lors de la récupération de l'historique"));
        }
    }

    /**
     * DELETE /api/chatbot/message/{messageId}
     * Delete a specific message
     */
    @DeleteMapping("/message/{messageId}")
    public ResponseEntity<?> deleteMessage(@PathVariable UUID messageId) {
        try {
            UUID userId = getCurrentUserId();
            log.info("Delete message request from user: {} for message: {}", userId, messageId);

            boolean deleted = chatbotService.deleteMessage(messageId, userId);

            if (!deleted) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new ErrorResponse("Impossible de supprimer ce message"));
            }

            return ResponseEntity.ok(new HashMap<String, String>() {{
                put("message", "Message supprimé avec succès");
            }});

        } catch (Exception e) {
            log.error("Error deleting message", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("Erreur lors de la suppression"));
        }
    }

    /**
     * DELETE /api/chatbot/history
     * Clear entire conversation history
     */
    @DeleteMapping("/history")
    public ResponseEntity<?> clearHistory() {
        try {
            UUID userId = getCurrentUserId();
            log.info("Clear conversation history for user: {}", userId);

            chatbotService.clearConversationHistory(userId);

            return ResponseEntity.ok(new HashMap<String, String>() {{
                put("message", "Historique de conversation supprimé");
            }});

        } catch (Exception e) {
            log.error("Error clearing conversation history", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("Erreur lors de la suppression de l'historique"));
        }
    }

    /**
     * Get current authenticated user ID from JWT (which contains email)
     */
    private UUID getCurrentUserId() {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null) {
                // Get email from JWT principal
                String email = authentication.getName();
                log.debug("Extracted email from JWT: {}", email);

                // Look up user by email to get UUID
                User user = userRepository.findByEmail(email).orElse(null);
                if (user != null) {
                    log.debug("Found user with email {}: {}", email, user.getId());
                    return user.getId();
                } else {
                    log.warn("User not found with email: {}", email);
                }
            }
        } catch (Exception e) {
            log.error("Error getting current user ID", e);
        }
        throw new RuntimeException("User not authenticated");
    }

    /**
     * Error response class
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    public static class ErrorResponse {
        private String error;
    }
}
