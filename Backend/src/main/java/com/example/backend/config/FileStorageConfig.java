package com.example.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import jakarta.annotation.PostConstruct;

import java.io.File;

//Configuration pour la gestion du stockage de fichiers

@Configuration
public class FileStorageConfig {

    @Value("${app.upload.dir:uploads/projects/}")
    private String uploadDir;

    private static final long MAX_FILE_SIZE = 50 * 1024 * 1024; // 50 MB


// Initialise le dossier de stockage après que Spring ait injecté les propriétés

    @PostConstruct
    public void initializeUploadDirectory() {
        if (uploadDir == null || uploadDir.isEmpty()) {
            uploadDir = "uploads/projects/";
        }
        File uploadDirectory = new File(uploadDir);
        if (!uploadDirectory.exists()) {
            if (uploadDirectory.mkdirs()) {
                System.out.println("Dossier de téléchargement créé: " + uploadDir);
            } else {
                System.err.println("Impossible de créer le dossier de téléchargement: " + uploadDir);
            }
        }
    }

    public String getUploadDir() {
        return uploadDir;
    }

    public long getMaxFileSize() {
        return MAX_FILE_SIZE;
    }
}
