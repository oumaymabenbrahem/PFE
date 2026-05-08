package com.example.backend.service;

import com.example.backend.dto.AuthResponse;
import com.example.backend.dto.LoginRequest;
import com.example.backend.dto.RegisterRequest;
import com.example.backend.entity.User;
import com.example.backend.repository.UserRepository;
import com.example.backend.security.CustomUserDetails;
import com.example.backend.security.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import java.util.Arrays;
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
