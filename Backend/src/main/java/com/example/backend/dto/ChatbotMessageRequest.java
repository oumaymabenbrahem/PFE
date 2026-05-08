package com.example.backend.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Request DTO for chat messages
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatbotMessageRequest {
    private String userMessage;
    private boolean includeContext; // Whether to include user data context
}
