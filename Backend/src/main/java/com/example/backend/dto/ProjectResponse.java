package com.example.backend.dto;

import com.example.backend.entity.enums.ProjectStatus;
import com.example.backend.entity.enums.SpecificationType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectResponse {

    private UUID id;

    private String nom;

    private String description;

    private SpecificationType specificationType;

    private String specificationContenu;

    private String focusOptionnel;

    private ProjectStatus statut;

    private String ownerName;

    private String ownerEmail;

    private int nombreScripts;

    private String fichierGenere;

    private String executionLogs;

    private LocalDateTime createdAt;

    private String fichierNom;

    private String fichierType;

    private Long fichierTaille;
}
