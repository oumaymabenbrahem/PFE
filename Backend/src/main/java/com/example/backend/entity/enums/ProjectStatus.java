package com.example.backend.entity.enums;

//États d'un projet

public enum ProjectStatus {
    EN_ATTENTE,  // Projet créé, en attente de traitement IA
    EN_COURS,    // L'IA génère les scripts
    TERMINE,     // Scripts générés, prêts à tester
    ERREUR       // Problème lors de la génération
}
