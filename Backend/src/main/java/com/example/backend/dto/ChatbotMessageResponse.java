package com.example.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Response DTO for chat messages
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatbotMessageResponse {
    private UUID id;
    private String userMessage;
    private String botResponse;
    private String messageType; // GENERAL, CONTEXTUAL, ERROR, SUGGESTION
    private String contextData; // Context used for generating response (JSON string)
    private LocalDateTime createdAt;
}
