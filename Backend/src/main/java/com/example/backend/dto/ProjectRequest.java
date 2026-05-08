package com.example.backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectRequest {

    @NotBlank(message = "Le nom du projet est obligatoire")
    private String nom;

    private String description;

    @NotBlank(message = "Le type de spécification est obligatoire")
    private String specificationType;

    private String specificationContenu;

    private String focusOptionnel;
}
