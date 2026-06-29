package com.example.backend.entity.enums;

import lombok.Getter;

/**
 * énumération des rôles utilisateur pour la sécurité et la cohérence des données.
 */
@Getter
public enum Role {
    ROLE_USER("Utilisateur standard"),
    ROLE_ADMIN("Administrateur plateforme");

    private final String description;

    Role(String description) {
        this.description = description;
    }
}
