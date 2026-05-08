package com.example.backend.repository;

import com.example.backend.entity.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    /**
     * Trouve un token OTP non vérifié et non expiré pour un email donné
     */
    Optional<PasswordResetToken> findTopByEmailAndVerifiedFalseAndExpiresAtAfterOrderByCreatedAtDesc(
            String email, LocalDateTime now);

    /**
     * Trouve un token de reset validé (après vérification OTP)
     */
    Optional<PasswordResetToken> findByResetTokenAndVerifiedTrue(String resetToken);

    /**
     * Supprime tous les tokens associés à un email
     */
    void deleteByEmail(String email);

    /**
     * Compte le nombre de tokens créés pour un email après une date donnée (pour rate limiting)
     */
    long countByEmailAndCreatedAtAfter(String email, LocalDateTime since);
}
