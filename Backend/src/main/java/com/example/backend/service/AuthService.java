package com.example.backend.service;

import com.example.backend.dto.AuthResponse;
import com.example.backend.dto.LoginRequest;
import com.example.backend.dto.RegisterRequest;
import com.example.backend.entity.User;
import com.example.backend.repository.UserRepository;
import com.example.backend.security.CustomUserDetails;
import com.example.backend.security.JwtUtil;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

@Service
@Slf4j
public class AuthService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private AuthenticationManager authenticationManager;

    @Value("${google.oauth.client-id}")
    private String googleClientId;

    @Value("${github.oauth.client-id}")
    private String githubClientId;

    @Value("${github.oauth.client-secret}")
    private String githubClientSecret;

    @Value("${github.oauth.redirect-uri}")
    private String githubRedirectUri;

    /**
     * Enregistre un nouvel utilisateur
     */
    public AuthResponse register(RegisterRequest request) {
        // Vérifier que l'email n'existe pas déjà
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("Cet email est déjà utilisé");
        }

        // Vérifier les mots de passe
        if (!request.getPassword().equals(request.getConfirmPassword())) {
            throw new IllegalArgumentException("Les mots de passe ne correspondent pas");
        }

        // Créer un nouvel utilisateur
        User user = User.builder()
                .email(request.getEmail())
                .nom(request.getNom())
                .password(passwordEncoder.encode(request.getPassword()))
                .roles(Arrays.asList("ROLE_USER"))
                .build();

        userRepository.save(user);
        log.info("Nouvel utilisateur enregistré: {}", request.getEmail());

        // Générer le token avec CustomUserDetails contenant l'ID
        UserDetails userDetails = new CustomUserDetails(
                user.getEmail(), 
                user.getPassword(), 
                Arrays.asList(new SimpleGrantedAuthority("ROLE_USER")),
                user.getId()
        );
        String token = jwtUtil.generateToken(userDetails);

        return buildAuthResponse(user, token);
    }

    /**
     * Authentifie un utilisateur
     */
    public AuthResponse login(LoginRequest request) {
        // Authentifier l'utilisateur
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword())
        );

        // Récupérer l'utilisateur
        Optional<User> userOptional = userRepository.findByEmail(request.getEmail());
        if (userOptional.isEmpty()) {
            throw new IllegalArgumentException("Utilisateur non trouvé");
        }

        User user = userOptional.get();

        // Générer le token
        String token = jwtUtil.generateToken((UserDetails) authentication.getPrincipal());
        log.info("Utilisateur authentifié: {}", request.getEmail());

        return buildAuthResponse(user, token);
    }

    /**
     * Authentifie un utilisateur via Google OAuth2
     * Vérifie le token Google, crée ou récupère l'utilisateur, et émet un JWT
     */
    public AuthResponse googleLogin(String googleToken) {
        try {
            // Vérifier le token Google ID
            GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier.Builder(
                    new NetHttpTransport(),
                    GsonFactory.getDefaultInstance())
                    .setAudience(Collections.singletonList(googleClientId))
                    .build();

            GoogleIdToken idToken = verifier.verify(googleToken);
            if (idToken == null) {
                throw new IllegalArgumentException("Token Google invalide");
            }

            // Extraire les informations du payload Google
            GoogleIdToken.Payload payload = idToken.getPayload();
            String email = payload.getEmail();
            String name = (String) payload.get("name");
            if (name == null || name.isBlank()) {
                name = email.split("@")[0];
            }

            // Trouver ou créer l'utilisateur
            Optional<User> existingUser = userRepository.findByEmail(email);
            User user;

            if (existingUser.isPresent()) {
                user = existingUser.get();
                log.info("Utilisateur Google existant connecté: {}", email);
            } else {
                // Créer un nouvel utilisateur avec un mot de passe aléatoire
                user = User.builder()
                        .email(email)
                        .nom(name)
                        .password(passwordEncoder.encode(java.util.UUID.randomUUID().toString()))
                        .roles(Arrays.asList("ROLE_USER"))
                        .build();
                userRepository.save(user);
                log.info("Nouvel utilisateur Google créé: {}", email);
            }

            // Générer le token JWT
            UserDetails userDetails = new CustomUserDetails(
                    user.getEmail(),
                    user.getPassword(),
                    Arrays.asList(new SimpleGrantedAuthority("ROLE_USER")),
                    user.getId()
            );
            String token = jwtUtil.generateToken(userDetails);

            return buildAuthResponse(user, token);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("Erreur lors de l'authentification Google: {}", e.getMessage());
            throw new IllegalArgumentException("Erreur lors de l'authentification Google: " + e.getMessage());
        }
    }

    /**
     * Authentifie un utilisateur via GitHub OAuth2
     * Échange le code d'autorisation contre un access token, récupère les infos utilisateur
     */
    public AuthResponse githubLogin(String code) {
        try {
            // Étape 1 : Échanger le code contre un access token
            RestTemplate restTemplate = new RestTemplate();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("client_id", githubClientId);
            body.add("client_secret", githubClientSecret);
            body.add("code", code);
            body.add("redirect_uri", githubRedirectUri);

            HttpEntity<MultiValueMap<String, String>> tokenRequest = new HttpEntity<>(body, headers);

            ResponseEntity<String> tokenResponse = restTemplate.exchange(
                    "https://github.com/login/oauth/access_token",
                    HttpMethod.POST,
                    tokenRequest,
                    String.class
            );

            ObjectMapper mapper = new ObjectMapper();
            JsonNode tokenNode = mapper.readTree(tokenResponse.getBody());
            String accessToken = tokenNode.get("access_token").asText();

            if (accessToken == null || accessToken.isBlank()) {
                throw new IllegalArgumentException("Code GitHub invalide");
            }

            // Étape 2 : Récupérer les infos utilisateur GitHub
            HttpHeaders userHeaders = new HttpHeaders();
            userHeaders.setBearerAuth(accessToken);
            userHeaders.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

            HttpEntity<Void> userRequest = new HttpEntity<>(userHeaders);

            ResponseEntity<String> userResponse = restTemplate.exchange(
                    "https://api.github.com/user",
                    HttpMethod.GET,
                    userRequest,
                    String.class
            );

            JsonNode userNode = mapper.readTree(userResponse.getBody());
            String email = userNode.has("email") && !userNode.get("email").isNull()
                    ? userNode.get("email").asText()
                    : null;
            String name = userNode.has("name") && !userNode.get("name").isNull()
                    ? userNode.get("name").asText()
                    : userNode.get("login").asText();
            String login = userNode.get("login").asText();

            // Si l'email n'est pas public, récupérer les emails GitHub
            if (email == null) {
                ResponseEntity<String> emailsResponse = restTemplate.exchange(
                        "https://api.github.com/user/emails",
                        HttpMethod.GET,
                        userRequest,
                        String.class
                );

                JsonNode emailsNode = mapper.readTree(emailsResponse.getBody());
                for (JsonNode emailNode : emailsNode) {
                    if (emailNode.has("primary") && emailNode.get("primary").asBoolean()) {
                        email = emailNode.get("email").asText();
                        break;
                    }
                }
            }

            if (email == null) {
                email = login + "@github.com";
            }

            // Trouver ou créer l'utilisateur
            Optional<User> existingUser = userRepository.findByEmail(email);
            User user;

            if (existingUser.isPresent()) {
                user = existingUser.get();
                log.info("Utilisateur GitHub existant connecté: {}", email);
            } else {
                user = User.builder()
                        .email(email)
                        .nom(name)
                        .password(passwordEncoder.encode(java.util.UUID.randomUUID().toString()))
                        .roles(Arrays.asList("ROLE_USER"))
                        .build();
                userRepository.save(user);
                log.info("Nouvel utilisateur GitHub créé: {}", email);
            }

            // Générer le token JWT
            UserDetails userDetails = new CustomUserDetails(
                    user.getEmail(),
                    user.getPassword(),
                    Arrays.asList(new SimpleGrantedAuthority("ROLE_USER")),
                    user.getId()
            );
            String token = jwtUtil.generateToken(userDetails);

            return buildAuthResponse(user, token);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("Erreur lors de l'authentification GitHub: {}", e.getMessage());
            throw new IllegalArgumentException("Erreur lors de l'authentification GitHub: " + e.getMessage());
        }
    }

    /**
     * Construit une réponse d'authentification
     */
    private AuthResponse buildAuthResponse(User user, String token) {
        AuthResponse.UserDTO userDTO = AuthResponse.UserDTO.builder()
                .id(user.getId())
                .email(user.getEmail())
                .nom(user.getNom())
                .roles(user.getRoles())
                .build();

        return AuthResponse.builder()
                .token(token)
                .user(userDTO)
                .build();
    }
}
