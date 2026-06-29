package com.example.backend.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.persistence.*;
import java.util.List;
import java.util.UUID;
import com.example.backend.entity.enums.Role;
import jakarta.persistence.Convert;
import com.example.backend.security.StringCryptoConverter;

@Entity
@Table(name = "users")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false, length = 255)
    private String nom;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "role")
    private List<Role> roles;

    @Column(nullable = false, updatable = false)
    private Long createdAt;

    @Convert(converter = StringCryptoConverter.class)
    @Column(columnDefinition = "TEXT")
    private String jiraAccessToken;

    @Convert(converter = StringCryptoConverter.class)
    @Column(columnDefinition = "TEXT")
    private String jiraRefreshToken;

    @Column(length = 255)
    private String jiraCloudId;

    @Convert(converter = StringCryptoConverter.class)
    @Column(columnDefinition = "TEXT")
    private String xrayClientId;

    @Convert(converter = StringCryptoConverter.class)
    @Column(columnDefinition = "TEXT")
    private String xrayClientSecret;

    @Column(length = 255)
    private String xrayBaseUrl;

    @PrePersist
    protected void onCreate() {
        createdAt = System.currentTimeMillis();
    }

    @OneToMany(mappedBy = "owner", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Project> ownedProjects;

    @ManyToMany(mappedBy = "members")
    private List<Project> joinedProjects;
}
