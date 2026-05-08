package com.example.backend.repository;

import com.example.backend.entity.Project;
import com.example.backend.entity.enums.ProjectStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProjectRepository extends JpaRepository<Project, UUID> {

   // Trouver tous les projets d'un propriétaire

    List<Project> findByOwnerId(UUID ownerId);

    // Trouver tous les projets auxquels un utilisateur appartient en tant que membre

    List<Project> findByMembersId(UUID userId);

   //Trouver un projet par ID si l'utilisateur en est propriétaire

    Optional<Project> findByIdAndOwnerId(UUID id, UUID ownerId);

   // Trouver tous les projets avec un statut spécifique

    List<Project> findByStatut(ProjectStatus statut);

    // Vérifier si un projet existe et appartient à un utilisateur

    boolean existsByIdAndOwnerId(UUID id, UUID ownerId);
}
