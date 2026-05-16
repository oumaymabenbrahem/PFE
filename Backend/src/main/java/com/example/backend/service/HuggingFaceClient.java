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

import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;

/**
 * Service for calling Hugging Face API with OpenAI-compatible endpoint (Mistral model)
 * For free tier: https://huggingface.co/settings/tokens
 */
@Service
@Slf4j
public class HuggingFaceClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private boolean apiAvailable = false;

    @Value("${huggingface.api-key:}")
    private String apiKey;

    @Value("${huggingface.model:mistral-7b-instruct}")
    private String model;

    @Value("${huggingface.endpoint:https://api-inference.huggingface.co/v1/chat/completions}")
    private String endpoint;

    public HuggingFaceClient(ObjectMapper objectMapper) {
        this.restTemplate = new RestTemplate();
        this.objectMapper = objectMapper;
    }

    /**
     * Startup: Initialize and test API availability
     */
    @PostConstruct
    public void startup() {
        initialize();
    }

    /**
     * Initialize: Test API availability at startup
     */
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
            log.warn("✗ Hugging Face API is not available - will use fallback responses", e.getMessage());
        }
    }

    /**
     * Call Hugging Face API with standard Inference API
     * Zephyr-7B for better instruction following
     */
    public String generateResponse(String prompt) {
        // If API not available, throw immediately (don't retry)
        if (!apiAvailable) {
            throw new RuntimeException("Hugging Face API is not available");
        }

        try {
            log.info("Calling Hugging Face API with model: {}", model);

            // Build request in standard Hugging Face format
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + apiKey);
            headers.set("Content-Type", "application/json");

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("inputs", prompt);
            requestBody.put("parameters", Map.of(
                "max_new_tokens", 256,
                "temperature", 0.7,
                "top_p", 0.9
            ));

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            // Call API
            ResponseEntity<String> response = restTemplate.exchange(
                endpoint,
                HttpMethod.POST,
                entity,
                String.class
            );

            log.info("Hugging Face API response status: {}", response.getStatusCode());

            // Parse response
            return parseOpenAIResponse(response.getBody());

        } catch (Exception e) {
            log.error("Error calling Hugging Face API", e);
            throw new RuntimeException("Failed to generate response from Hugging Face API", e);
        }
    }

    /**
     * Parse Hugging Face API response format
     * Response format: [{"generated_text": "..."}]
     */
    private String parseOpenAIResponse(String responseBody) throws Exception {
        try {
            JsonNode jsonNode = objectMapper.readTree(responseBody);

            // Handle array response (Hugging Face standard format)
            if (jsonNode.isArray() && jsonNode.size() > 0) {
                String generatedText = jsonNode.get(0).get("generated_text").asText();
                return generatedText.trim();
            }

            // Handle OpenAI-compatible format
            if (jsonNode.has("choices") && jsonNode.get("choices").isArray() && jsonNode.get("choices").size() > 0) {
                String generatedText = jsonNode.get("choices").get(0).get("message").get("content").asText();
                return generatedText.trim();
            }

            log.warn("Unexpected API response format: {}", responseBody);
            return "Je n'ai pas pu générer une réponse. Veuillez réessayer.";

        } catch (Exception e) {
            log.error("Error parsing API response", e);
            throw new RuntimeException("Failed to parse API response", e);
        }
    }

    /**
     * Test API connection with a simple request
     */
    private void testAPIConnection() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + apiKey);
        headers.set("Content-Type", "application/json");

        Map<String, Object> testRequest = new HashMap<>();
        testRequest.put("inputs", "Hello");
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

    /**
     * Check if API is actually available (not just configured)
     */
    public boolean isAvailable() {
        return apiAvailable;
    }

    /**
     * Validate API key configuration
     */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isEmpty() && !apiKey.equals("YOUR_HUGGING_FACE_API_KEY");
    }

    /**
     * Inner class representing a chat message for conversation history
     */
    @Getter
    @Setter
    @AllArgsConstructor
    public static class ChatMessage {
        private String userMessage;
        private String botResponse;
    }
}
