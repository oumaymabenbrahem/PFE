package com.example.backend.controller;

import com.example.backend.dto.*;
import com.example.backend.service.AuthService;
import com.example.backend.service.PasswordResetService;
import jakarta.validation.Valid;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "http://localhost:4200")
@Slf4j
public class AuthController {

    @Autowired
    private AuthService authService;

    @Autowired
    private PasswordResetService passwordResetService;

    // Endpoint d'enregistrement

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest request) {
        try {
            log.info("Tentative d'enregistrement pour: {}", request.getEmail());
            AuthResponse response = authService.register(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException e) {
            log.warn("Erreur lors de l'enregistrement: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse("Erreur d'enregistrement: " + e.getMessage()));
        } catch (Exception e) {
            log.error("Erreur interne lors de l'enregistrement", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("Erreur interne du serveur"));
        }
    }

    // Endpoint de connexion

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        try {
            log.info("Tentative de connexion pour: {}", request.getEmail());
            AuthResponse response = authService.login(request);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.warn("Erreur lors de la connexion: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse("Identifiants invalides"));
        } catch (Exception e) {
            log.error("Erreur interne lors de la connexion", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("Erreur interne du serveur"));
        }
    }

    // Endpoint de connexion Google OAuth2

    @PostMapping("/google")
    public ResponseEntity<?> googleLogin(@Valid @RequestBody GoogleAuthRequest request) {
        try {
            log.info("Tentative de connexion Google OAuth2");
            AuthResponse response = authService.googleLogin(request.getToken());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.warn("Erreur lors de la connexion Google: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse(e.getMessage()));
        } catch (Exception e) {
            log.error("Erreur interne lors de la connexion Google", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("Erreur interne du serveur"));
        }
    }

    // Endpoint de connexion GitHub OAuth2

    @PostMapping("/github")
    public ResponseEntity<?> githubLogin(@Valid @RequestBody GitHubAuthRequest request) {
        try {
            log.info("Tentative de connexion GitHub OAuth2");
            AuthResponse response = authService.githubLogin(request.getCode());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.warn("Erreur lors de la connexion GitHub: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse(e.getMessage()));
        } catch (Exception e) {
            log.error("Erreur interne lors de la connexion GitHub", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("Erreur interne du serveur"));
        }
    }

    // ==================== MOT DE PASSE OUBLIÉ ====================

    /**
     * Étape 1 : L'utilisateur soumet son email
     * Génère un OTP et l'envoie par email
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        try {
            log.info("Demande de réinitialisation pour: {}", request.getEmail());
            passwordResetService.requestPasswordReset(request.getEmail());
            return ResponseEntity.ok(Map.of(
                    "message", "Un code de vérification a été envoyé à votre adresse email"
            ));
        } catch (IllegalArgumentException e) {
            log.warn("Erreur forgot-password: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse(e.getMessage()));
        } catch (Exception e) {
            log.error("Erreur interne lors de forgot-password", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("Erreur lors de l'envoi du code. Veuillez réessayer"));
        }
    }

    /**
     * Étape 2 : L'utilisateur saisit le code OTP
     * Vérifie le code et retourne un resetToken temporaire
     */
    @PostMapping("/verify-otp")
    public ResponseEntity<?> verifyOtp(@Valid @RequestBody VerifyOtpRequest request) {
        try {
            log.info("Vérification OTP pour: {}", request.getEmail());
            String resetToken = passwordResetService.verifyOtp(request.getEmail(), request.getOtpCode());
            return ResponseEntity.ok(Map.of(
                    "message", "Code vérifié avec succès",
                    "resetToken", resetToken
            ));
        } catch (IllegalArgumentException e) {
            log.warn("Erreur verify-otp: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse(e.getMessage()));
        } catch (Exception e) {
            log.error("Erreur interne lors de verify-otp", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("Erreur lors de la vérification. Veuillez réessayer"));
        }
    }

    /**
     * Étape 3 : L'utilisateur crée son nouveau mot de passe
     * Vérifie le resetToken et met à jour le mot de passe
     */
    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        try {
            log.info("Réinitialisation du mot de passe en cours...");
            passwordResetService.resetPassword(request.getResetToken(), request.getNewPassword());
            return ResponseEntity.ok(Map.of(
                    "message", "Votre mot de passe a été réinitialisé avec succès"
            ));
        } catch (IllegalArgumentException e) {
            log.warn("Erreur reset-password: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse(e.getMessage()));
        } catch (Exception e) {
            log.error("Erreur interne lors de reset-password", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("Erreur lors de la réinitialisation. Veuillez réessayer"));
        }
    }

    /**
     * Classe interne pour les réponses d'erreur
     */
    public static class ErrorResponse {
        public String message;

        public ErrorResponse(String message) {
            this.message = message;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }
}
