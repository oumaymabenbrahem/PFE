package com.example.backend.config;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson configuration for handling large JSON responses.
 * Increases the maximum string length limit to accommodate large test execution results.
 */
@Configuration
public class JacksonConfig {

    /**
     * Configure ObjectMapper with custom stream constraints.
     * Sets maximum string length to 100MB (104857600 bytes) to handle large responses
     * from Python AI service test executions.
     */
    @Bean
    public ObjectMapper objectMapper() {
        JsonFactory jsonFactory = JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxStringLength(104857600) // 100MB limit
                        .build())
                .build();

        return new ObjectMapper(jsonFactory);
    }
}
