package com.example.backend.repository;

import com.example.backend.entity.ChatbotMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ChatbotMessageRepository extends JpaRepository<ChatbotMessage, UUID> {

    /**
     * Get all messages for a user, ordered by creation date (newest first)
     */
    List<ChatbotMessage> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /**
     * Get last N messages for a user
     */
    List<ChatbotMessage> findTop50ByUserIdOrderByCreatedAtDesc(UUID userId);

    /**
     * Delete all messages for a user (when user is deleted)
     */
    void deleteByUserId(UUID userId);

    /**
     * Count total messages for a user
     */
    long countByUserId(UUID userId);
}
