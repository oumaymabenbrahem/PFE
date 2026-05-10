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

import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service for calling Hugging Face Inference API
 * Uses Zephyr chat template format with conversation history
 */
@Service
@Slf4j
public class HuggingFaceClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private boolean apiAvailable = false;

    @Value("${huggingface.api-key:}")
    private String apiKey;

    @Value("${huggingface.model:HuggingFaceH4/zephyr-7b-beta}")
    private String model;

    @Value("${huggingface.endpoint:https://api-inference.huggingface.co/models/HuggingFaceH4/zephyr-7b-beta}")
    private String endpoint;

    public HuggingFaceClient(ObjectMapper objectMapper) {
        this.restTemplate = new RestTemplate();
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void startup() {
        initialize();
    }

    public void initialize() {
        if (!isConfigured()) {
            log.warn("Hugging Face API not configured");
            apiAvailable = false;
            return;
        }

        try {
            log.info("Testing Hugging Face API connectivity...");
            testAPIConnection();
            apiAvailable = true;
            log.info("✓ Hugging Face API is available");
        } catch (Exception e) {
            apiAvailable = false;
            log.warn("✗ Hugging Face API is not available - will use fallback responses: {}", e.getMessage());
        }
    }

    /**
     * Generate response with conversation history using Zephyr chat template
     * Zephyr format: <|system|>\n{system}<|end|>\n<|user|>\n{user}<|end|>\n<|assistant|>\n{assistant}<|end|>
     */
    public String generateResponse(String systemPrompt, List<ChatMessage> conversationHistory, String userMessage) {
        if (!apiAvailable) {
            throw new RuntimeException("Hugging Face API is not available");
        }

        try {
            log.info("Calling Hugging Face API with model: {} (history: {} messages)", model, conversationHistory.size());

            // Build prompt using Zephyr chat template
            String formattedPrompt = buildZephyrPrompt(systemPrompt, conversationHistory, userMessage);

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + apiKey);
            headers.set("Content-Type", "application/json");

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("inputs", formattedPrompt);
            requestBody.put("parameters", Map.of(
                "max_new_tokens", 512,
                "temperature", 0.7,
                "top_p", 0.9,
                "return_full_text", false
            ));

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                endpoint,
                HttpMethod.POST,
                entity,
                String.class
            );

            log.debug("Hugging Face API response status: {}", response.getStatusCode());

            return parseResponse(response.getBody());

        } catch (Exception e) {
            log.error("Error calling Hugging Face API", e);
            throw new RuntimeException("Failed to generate response from Hugging Face API", e);
        }
    }

    /**
     * Legacy method for backward compatibility
     */
    public String generateResponse(String prompt) {
        return generateResponse(null, List.of(), prompt);
    }

    /**
     * Build prompt using Zephyr-7B chat template format
     */
    private String buildZephyrPrompt(String systemPrompt, List<ChatMessage> conversationHistory, String userMessage) {
        StringBuilder prompt = new StringBuilder();

        // System message
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            prompt.append("<|system|>\n").append(systemPrompt).append("</s>\n");
        }

        // Conversation history
        for (ChatMessage msg : conversationHistory) {
            prompt.append("<|user|>\n").append(msg.userMessage()).append("</s>\n");
            prompt.append("<|assistant|>\n").append(msg.botResponse()).append("</s>\n");
        }

        // Current user message
        prompt.append("<|user|>\n").append(userMessage).append("</s>\n");
        prompt.append("<|assistant|>\n");

        return prompt.toString();
    }

    /**
     * Parse API response - handles Hugging Face Inference API format
     */
    private String parseResponse(String responseBody) throws Exception {
        try {
            JsonNode jsonNode = objectMapper.readTree(responseBody);

            // Handle array response (Hugging Face standard Inference API format)
            if (jsonNode.isArray() && jsonNode.size() > 0) {
                JsonNode firstItem = jsonNode.get(0);
                if (firstItem.has("generated_text")) {
                    String generatedText = firstItem.get("generated_text").asText().trim();
                    // Clean up any remaining template tokens
                    generatedText = generatedText.replace("</s>", "").replace("<|end|>", "").trim();
                    return generatedText;
                }
            }

            // Handle OpenAI-compatible format (for dedicated endpoints)
            if (jsonNode.has("choices") && jsonNode.get("choices").isArray() && jsonNode.get("choices").size() > 0) {
                JsonNode firstChoice = jsonNode.get("choices").get(0);
                if (firstChoice.has("message") && firstChoice.get("message").has("content")) {
                    return firstChoice.get("message").get("content").asText().trim();
                }
            }

            log.warn("Unexpected API response format: {}", responseBody.substring(0, Math.min(200, responseBody.length())));
            return "Je n'ai pas pu générer une réponse. Veuillez réessayer.";

        } catch (Exception e) {
            log.error("Error parsing API response", e);
            throw new RuntimeException("Failed to parse API response", e);
        }
    }

    private void testAPIConnection() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + apiKey);
        headers.set("Content-Type", "application/json");

        Map<String, Object> testRequest = new HashMap<>();
        testRequest.put("inputs", "<|user|>\nHello</s>\n<|assistant|>\n");
        testRequest.put("parameters", Map.of("max_new_tokens", 20));

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(testRequest, headers);

        ResponseEntity<String> response = restTemplate.exchange(
            endpoint,
            HttpMethod.POST,
            entity,
            String.class
        );

        if (response.getStatusCode().is2xxSuccessful()) {
            log.info("API test successful");
        } else {
            throw new RuntimeException("API returned status: " + response.getStatusCode());
        }
    }

    public boolean isAvailable() {
        return apiAvailable;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isEmpty() && !apiKey.equals("YOUR_HUGGING_FACE_API_KEY");
    }

    /**
     * Simple record for conversation history entries
     */
    public record ChatMessage(String userMessage, String botResponse) {}
}
