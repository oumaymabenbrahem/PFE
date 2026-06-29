package com.example.backend.service;

import com.example.backend.entity.PasswordResetToken;
import com.example.backend.entity.User;
import com.example.backend.repository.PasswordResetTokenRepository;
import com.example.backend.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
public class PasswordResetService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordResetTokenRepository resetTokenRepository;

    @Autowired
    private EmailService emailService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Value("${app.otp.expiration-minutes}")
    private int otpExpirationMinutes;

    @Value("${app.otp.max-resend-attempts}")
    private int maxResendAttempts;

    @Value("${app.otp.resend-block-minutes}")
    private int resendBlockMinutes;

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Étape 1 : Demande de réinitialisation du mot de passe
     * - Vérifie que l'email existe
     * - Vérifie le rate limiting (3 renvois max, puis blocage 15 min)
     * - Génère un OTP à 6 chiffres
     * - Sauvegarde en DB avec expiration
     * - Envoie l'email
     */
    @Transactional
    public void requestPasswordReset(String email) {
        // Vérifier que l'email existe dans la base
        Optional<User> userOpt = userRepository.findByEmail(email);
        if (userOpt.isEmpty()) {
            log.warn("Tentative de reset pour un email inexistant: {}", email);
            // On ne révèle pas si l'email existe ou non (sécurité)
            // Mais on lève une exception pour informer le frontend
            throw new IllegalArgumentException("Aucun compte n'est associé à cet email");
        }

        // Vérifier le rate limiting : nombre de renvois dans les dernières 15 minutes
        LocalDateTime blockWindow = LocalDateTime.now().minusMinutes(resendBlockMinutes);
        long recentAttempts = resetTokenRepository.countByEmailAndCreatedAtAfter(email, blockWindow);

        if (recentAttempts >= maxResendAttempts) {
            log.warn("Rate limit atteint pour l'email: {} ({} tentatives)", email, recentAttempts);
            throw new IllegalArgumentException(
                "Trop de tentatives. Veuillez réessayer dans " + resendBlockMinutes + " minutes"
            );
        }

        // Générer un code OTP à 6 chiffres
        String otpCode = generateOtp();

        // Créer et sauvegarder le token
        PasswordResetToken resetToken = PasswordResetToken.builder()
                .user(userOpt.get())
                .email(email)
                .otpCode(otpCode)
                .expiresAt(LocalDateTime.now().plusMinutes(otpExpirationMinutes))
                .verified(false)
                .attempts(0)
                .resendCount((int) recentAttempts + 1)
                .build();

        resetTokenRepository.save(resetToken);
        log.info("OTP généré pour l'email: {}", email);

        // Envoyer l'email avec le code OTP
        emailService.sendOtpEmail(email, otpCode);
    }

    /**
     * Étape 2 : Vérification du code OTP
     * - Compare le code saisi avec celui en DB
     * - Vérifie l'expiration
     * - Génère un resetToken temporaire si valide
     */
    @Transactional
    public String verifyOtp(String email, String otpCode) {
        // Chercher le dernier OTP non vérifié et non expiré
        Optional<PasswordResetToken> tokenOpt = resetTokenRepository
                .findTopByEmailAndVerifiedFalseAndExpiresAtAfterOrderByCreatedAtDesc(
                        email, LocalDateTime.now());

        if (tokenOpt.isEmpty()) {
            log.warn("Aucun OTP valide trouvé pour: {}", email);
            throw new IllegalArgumentException("Code expiré ou invalide. Veuillez en demander un nouveau");
        }

        PasswordResetToken token = tokenOpt.get();

        // Vérifier le nombre de tentatives (max 5 essais par OTP)
        if (token.getAttempts() >= 5) {
            log.warn("Trop de tentatives de vérification OTP pour: {}", email);
            throw new IllegalArgumentException("Trop de tentatives incorrectes. Veuillez demander un nouveau code");
        }

        // Comparer les codes
        if (!token.getOtpCode().equals(otpCode)) {
            token.setAttempts(token.getAttempts() + 1);
            resetTokenRepository.save(token);
            log.warn("Code OTP incorrect pour: {} (tentative {})", email, token.getAttempts());
            throw new IllegalArgumentException("Le code saisi est incorrect");
        }

        // Code valide : générer un resetToken UUID
        String resetTokenValue = UUID.randomUUID().toString();
        token.setVerified(true);
        token.setResetToken(resetTokenValue);
        // Étendre l'expiration de 15 minutes pour le reset du mot de passe
        token.setExpiresAt(LocalDateTime.now().plusMinutes(15));
        resetTokenRepository.save(token);

        log.info("OTP vérifié avec succès pour: {}", email);
        return resetTokenValue;
    }

    /**
     * Étape 3 : Réinitialisation du mot de passe
     * - Vérifie le resetToken
     * - Hash le nouveau mot de passe
     * - Met à jour le User
     * - Supprime le token de la DB
     */
    @Transactional
    public void resetPassword(String resetTokenValue, String newPassword) {
        // Chercher le token de reset validé
        Optional<PasswordResetToken> tokenOpt = resetTokenRepository
                .findByResetTokenAndVerifiedTrue(resetTokenValue);

        if (tokenOpt.isEmpty()) {
            log.warn("Reset token invalide: {}", resetTokenValue);
            throw new IllegalArgumentException("Lien de réinitialisation invalide ou expiré");
        }

        PasswordResetToken token = tokenOpt.get();

        // Vérifier l'expiration
        if (token.getExpiresAt().isBefore(LocalDateTime.now())) {
            log.warn("Reset token expiré pour: {}", token.getEmail());
            resetTokenRepository.delete(token);
            throw new IllegalArgumentException("Le lien de réinitialisation a expiré. Veuillez recommencer");
        }

        // Récupérer l'utilisateur
        User user = userRepository.findByEmail(token.getEmail())
                .orElseThrow(() -> new IllegalArgumentException("Utilisateur non trouvé"));

        // Hasher et mettre à jour le mot de passe
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        // Supprimer tous les tokens de reset pour cet email
        resetTokenRepository.deleteByEmail(token.getEmail());

        log.info("Mot de passe réinitialisé avec succès pour: {}", token.getEmail());
    }

    /**
     * Génère un code OTP à 6 chiffres aléatoire
     */
    private String generateOtp() {
        int otp = 100000 + secureRandom.nextInt(900000);
        return String.valueOf(otp);
    }
}
