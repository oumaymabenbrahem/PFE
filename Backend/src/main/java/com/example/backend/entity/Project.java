package com.example.backend.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.example.backend.entity.enums.SpecificationType;
import com.example.backend.entity.enums.ProjectStatus;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "projects")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    @NotBlank(message = "Le nom du projet est obligatoire")
    private String nom;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SpecificationType specificationType;

    @Column(columnDefinition = "TEXT")
    private String specificationContenu;

    @Column(columnDefinition = "TEXT")
    private String focusOptionnel;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private ProjectStatus statut = ProjectStatus.EN_ATTENTE;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "project_members",
            joinColumns = @JoinColumn(name = "project_id"),
            inverseJoinColumns = @JoinColumn(name = "user_id")
    )
    private List<User> members;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "project", fetch = FetchType.LAZY, cascade = CascadeType.REMOVE, orphanRemoval = true)
    private List<TestScript> testScripts;

    @Column(name = "fichier_nom")
    private String fichierNom;

    @Column(name = "fichier_type")
    private String fichierType;

    @Column(name = "fichier_taille")
    private Long fichierTaille;

    @Column(name = "fichier_data", columnDefinition = "BYTEA")
    private byte[] fichierData;

    @Column(name = "fichier_genere")
    private String fichierGenere;

    @Column(name = "execution_logs", columnDefinition = "TEXT")
    private String executionLogs;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
