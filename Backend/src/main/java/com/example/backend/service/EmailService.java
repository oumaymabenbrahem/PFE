package com.example.backend.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.io.UnsupportedEncodingException;

@Service
@Slf4j
public class EmailService {

    @Autowired
    private JavaMailSender mailSender;

    @Value("${app.mail.from-address}")
    private String fromAddress;

    @Value("${app.mail.from-name}")
    private String fromName;

    /**
     * Envoie un email contenant le code OTP pour la réinitialisation du mot de passe
     * L'expéditeur affiché sera noreply@Test2i.com 
     */
    public void sendOtpEmail(String to, String otpCode) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            // Expéditeur visible : noreply@Test2i.com avec le nom "ST2i"
            helper.setFrom(new InternetAddress(fromAddress, fromName));
            helper.setTo(to);
            helper.setSubject("Code de réinitialisation de mot de passe - TEST2i");
            helper.setText(buildOtpEmailHtml(otpCode), true);

            mailSender.send(message);
            log.info("Email OTP envoyé avec succès à: {} (from: {})", to, fromAddress);
        } catch (MessagingException | UnsupportedEncodingException e) {
            log.error("Erreur lors de l'envoi de l'email OTP à {}: {}", to, e.getMessage());
            throw new RuntimeException("Impossible d'envoyer l'email de réinitialisation", e);
        }
    }

    /**
     * Construit le contenu HTML de l'email OTP
     */
    private String buildOtpEmailHtml(String otpCode) {
        String[] digits = otpCode.split("");

        StringBuilder digitBoxes = new StringBuilder();
        for (String digit : digits) {
            digitBoxes.append(
                "<div style=\"display:inline-block;width:42px;height:50px;margin:0 4px;" +
                "background:#f0f4f8;border:2px solid #1f4e8a;border-radius:10px;" +
                "font-size:24px;font-weight:700;color:#1f4e8a;line-height:50px;" +
                "text-align:center;font-family:'Segoe UI',Arial,sans-serif;\">"
                + digit + "</div>"
            );
        }

        return "<!DOCTYPE html>" +
            "<html><head><meta charset=\"UTF-8\"></head>" +
            "<body style=\"margin:0;padding:0;background:#eef2f7;font-family:'Segoe UI',Arial,sans-serif;\">" +
            "<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"padding:40px 0;\">" +
            "<tr><td align=\"center\">" +
            "<table width=\"460\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:#ffffff;border-radius:16px;" +
            "box-shadow:0 4px 24px rgba(0,0,0,0.08);overflow:hidden;\">" +

            // Header
            "<tr><td style=\"background:linear-gradient(135deg,#1f4e8a 0%,#2d6db5 100%);padding:32px 40px;text-align:center;\">" +
            "<h1 style=\"margin:0;color:#ffffff;font-size:22px;font-weight:700;\">Réinitialisation du mot de passe</h1>" +
            "</td></tr>" +

            // Body
            "<tr><td style=\"padding:36px 40px;\">" +
            "<p style=\"margin:0 0 8px;color:#333;font-size:15px;\">Bonjour,</p>" +
            "<p style=\"margin:0 0 24px;color:#555;font-size:14px;line-height:1.6;\">" +
            "Vous avez demandé la réinitialisation de votre mot de passe. " +
            "Utilisez le code ci-dessous pour continuer :</p>" +

            // OTP Code
            "<div style=\"text-align:center;margin:28px 0;\">" +
            digitBoxes.toString() +
            "</div>" +

            "<p style=\"margin:24px 0 0;color:#888;font-size:13px;text-align:center;\">" +
            "⏱ Ce code expire dans <strong>10 minutes</strong></p>" +

            "<hr style=\"margin:28px 0;border:none;border-top:1px solid #e8ecf1;\"/>" +

            "<p style=\"margin:0;color:#999;font-size:12px;line-height:1.5;\">" +
            "Si vous n'avez pas demandé cette réinitialisation, ignorez cet email. " +
            "Votre mot de passe restera inchangé.</p>" +
            "</td></tr>" +

            // Footer
            "<tr><td style=\"background:#f7f8fa;padding:20px 40px;text-align:center;" +
            "border-top:1px solid #eee;\">" +
            "<p style=\"margin:0;color:#aaa;font-size:12px;\">© 2026 TEST2i — Plateforme de test automatisé</p>" +
            "</td></tr>" +

            "</table></td></tr></table></body></html>";
    }
}
