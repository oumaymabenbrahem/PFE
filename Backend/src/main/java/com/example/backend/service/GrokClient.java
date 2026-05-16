package com.example.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service for calling Groq API (OpenAI-compatible).
 * Free tier: https://console.groq.com/
 */
@Service
@Slf4j
public class GrokClient {

    private static final String GROK_ENDPOINT = "https://api.groq.com/openai/v1/chat/completions";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${grok.api-key:}")
    private String apiKey;

    @Value("${grok.model:llama-3.3-70b-versatile}")
    private String model;

    public GrokClient(ObjectMapper objectMapper) {
        this.restTemplate = new RestTemplate();
        this.objectMapper = objectMapper;
    }

    /**
     * Check if Grok API is properly configured
     */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank() && !apiKey.equals("YOUR_GROK_API_KEY");
    }

    /**
     * Generate a response from Grok given a user prompt.
     *
     * @param systemPrompt the system/context prompt
     * @param userMessage  the raw user message
     * @return the assistant response text
     */
    public String generateResponse(String systemPrompt, String userMessage) {
        if (!isConfigured()) {
            throw new RuntimeException("Grok API key is not configured.");
        }

        try {
            log.info("Calling Grok API (model={})...", model);

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + apiKey);
            headers.set("Content-Type", "application/json");

            // Build OpenAI-compatible chat completion request
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            requestBody.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user",   "content", userMessage)
            ));
            requestBody.put("max_tokens", 1024);
            requestBody.put("temperature", 0.7);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                GROK_ENDPOINT,
                HttpMethod.POST,
                entity,
                String.class
            );

            log.info("Grok API response status: {}", response.getStatusCode());
            return parseResponse(response.getBody());

        } catch (Exception e) {
            log.error("Error calling Grok API", e);
            throw new RuntimeException("Failed to get response from Grok API: " + e.getMessage(), e);
        }
    }

    /**
     * Parse OpenAI-compatible response: choices[0].message.content
     */
    private String parseResponse(String responseBody) throws Exception {
        try {
            JsonNode root = objectMapper.readTree(responseBody);

            if (root.has("choices") && root.get("choices").isArray() && root.get("choices").size() > 0) {
                String content = root.get("choices").get(0).get("message").get("content").asText();
                return content.trim();
            }

            log.warn("Unexpected Grok API response format: {}", responseBody);
            return "Je n'ai pas pu obtenir une réponse. Veuillez réessayer.";

        } catch (Exception e) {
            log.error("Error parsing Grok API response", e);
            throw new RuntimeException("Failed to parse Grok API response", e);
        }
    }

    /**
     * Inner class representing a chat message (kept for compatibility)
     */
    @Getter
    @Setter
    @AllArgsConstructor
    public static class ChatMessage {
        private String userMessage;
        private String botResponse;
    }
}
