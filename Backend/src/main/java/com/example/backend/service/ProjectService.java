package com.example.backend.service;

import com.example.backend.dto.ProjectRequest;
import com.example.backend.dto.ProjectResponse;

import com.example.backend.entity.Project;
import com.example.backend.entity.User;
import com.example.backend.entity.enums.ProjectStatus;
import com.example.backend.entity.enums.SpecificationType;
import com.example.backend.exception.BadRequestException;
import com.example.backend.exception.ResourceNotFoundException;
import com.example.backend.repository.ProjectRepository;
import com.example.backend.repository.UserRepository;
import com.example.backend.repository.TestScriptRepository;
import com.example.backend.repository.TestExecutionResultRepository;
import com.example.backend.entity.TestScript;
import com.example.backend.entity.TestExecutionResult;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.openqa.selenium.support.ui.ExpectedConditions;
import java.time.Duration;

@Service
@Slf4j
public class ProjectService {

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestScriptRepository testScriptRepository;

    @Autowired
    private TestExecutionResultRepository testExecutionResultRepository;

    @Autowired
    private ObjectMapper objectMapper;


    private final List<String> ALLOWED_EXTENSIONS = Arrays.asList("zip", "html", "java", "py", "txt");

    /**
     * Crée un nouveau projet avec spécification (fichier, user story ou lien Git)
     */
    public ProjectResponse createProject(ProjectRequest request, MultipartFile fichier, UUID ownerId) {
        // Vérifier que le user existe
        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur non trouvé"));

        // Valider le type de spécification
        SpecificationType specificationType = validateAndParsSpecificationType(request.getSpecificationType());
        String specificationContenu = null;

        // Traiter selon le type de spécification
        byte[] _fileBytes = null;
        String _originalFilename = null;
        String _contentType = null;
        Long _fileSize = null;

        if (specificationType == SpecificationType.CODE_FICHIER) {
            // Fichier upload obligatoire
            if (fichier == null || fichier.isEmpty()) {
                throw new BadRequestException("Un fichier est obligatoire pour le type CODE_FICHIER");
            }
            validateFile(fichier);
            // Read bytes and metadata before saving to include in the entity
            try {
                _fileBytes = fichier.getBytes();
            } catch (IOException e) {
                log.error("Erreur lecture du fichier uploadé", e);
                throw new BadRequestException("Erreur lors de la lecture du fichier");
            }
            _originalFilename = fichier.getOriginalFilename();
            _contentType = fichier.getContentType();
            _fileSize = fichier.getSize();

            specificationContenu = _originalFilename;
        } else if (specificationType == SpecificationType.USER_STORY) {
            // Contenu user story obligatoire
            if (request.getSpecificationContenu() == null || request.getSpecificationContenu().trim().isEmpty()) {
                throw new BadRequestException("Le contenu de la user story est obligatoire");
            }
            specificationContenu = request.getSpecificationContenu();
        } else if (specificationType == SpecificationType.LIEN_GIT) {
            // URL GitHub obligatoire et valide
            if (request.getSpecificationContenu() == null || request.getSpecificationContenu().trim().isEmpty()) {
                throw new BadRequestException("L'URL GitHub est obligatoire");
            }
            if (!isValidGitHubUrl(request.getSpecificationContenu())) {
                throw new BadRequestException("L'URL GitHub n'est pas valide");
            }
            specificationContenu = request.getSpecificationContenu();
        } else if (specificationType == SpecificationType.LIEN_APPLICATION) {
            // URL Web obligatoire
            if (request.getSpecificationContenu() == null || request.getSpecificationContenu().trim().isEmpty()) {
                throw new BadRequestException("L'URL de l'application est obligatoire");
            }
            if (!request.getSpecificationContenu().matches("^https?://.+$")) {
                throw new BadRequestException("L'URL de l'application n'est pas valide");
            }
            specificationContenu = request.getSpecificationContenu();
        }

        // Créer le projet
        Project.ProjectBuilder projectBuilder = Project.builder()
                .nom(request.getNom())
                .description(request.getDescription())
                .specificationType(specificationType)
                .specificationContenu(specificationContenu)
                .focusOptionnel(request.getFocusOptionnel())
                .owner(owner)
                .members(new ArrayList<>())
                .statut(ProjectStatus.EN_ATTENTE);

        Project project = projectBuilder.build();

        // Safer: set file metadata/bytes via setters on the entity instance
        if (_originalFilename != null) {
            project.setFichierNom(_originalFilename);
        }
        if (_contentType != null) {
            project.setFichierType(_contentType);
        }
        if (_fileSize != null) {
            project.setFichierTaille(_fileSize);
        }
        if (_fileBytes != null) {
            project.setFichierData(_fileBytes);
        }

        // Debug: log file attachment details to help diagnose JDBC binding issues
        try {
            log.debug("Attaching fichier - nom: {}, type: {}, taille: {}, bytesPresent: {}",
                _originalFilename,
                _contentType,
                _fileSize,
                _fileBytes != null ? _fileBytes.length : 0);
        } catch (Exception ignored) {}

        Project savedProject = projectRepository.save(project);
        log.info("Projet créé: {} (type: {}) par {}", savedProject.getId(), specificationType, owner.getEmail());

        return mapToResponse(savedProject);
    }

    /**
     * Valide et parse le type de spécification
     */
    private SpecificationType validateAndParsSpecificationType(String type) {
        try {
            return SpecificationType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Type de spécification invalide: " + type + ". Valeurs acceptées: CODE_FICHIER, USER_STORY, LIEN_GIT, LIEN_APPLICATION");
        }
    }

    /**
     * Valide une URL GitHub
     */
    private boolean isValidGitHubUrl(String url) {
        return url != null && url.matches("^https://github\\.com/.+$");
    }

    /**
     * Récupère tous les projets d'un utilisateur (propriétaire ou membre)
     */
    public List<ProjectResponse> getProjectsByUser(UUID userId) {
        List<Project> ownedProjects = projectRepository.findByOwnerId(userId);
        List<Project> memberProjects = projectRepository.findByMembersId(userId);

        // Combiner et dédupliquer
        Set<Project> allProjects = new HashSet<>(ownedProjects);
        allProjects.addAll(memberProjects);

        return allProjects.stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    /**
     * Récupère un projet par ID
     */
    public ProjectResponse getProjectById(UUID id) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Projet non trouvé"));
        return mapToResponse(project);
    }

    /**
     * Met à jour un projet (seul le propriétaire peut le faire)
     */
    public ProjectResponse updateProject(UUID id, ProjectRequest request, MultipartFile fichier, UUID ownerId) {
        Project project = projectRepository.findByIdAndOwnerId(id, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Projet non trouvé ou accès refusé"));

        if (request.getNom() != null && !request.getNom().isEmpty()) {
            project.setNom(request.getNom());
        }
        if (request.getDescription() != null) {
            project.setDescription(request.getDescription());
        }
        if (request.getSpecificationContenu() != null) {
            project.setSpecificationContenu(request.getSpecificationContenu());
        }
        if (request.getFocusOptionnel() != null) {
            project.setFocusOptionnel(request.getFocusOptionnel());
        }

        // Mise à jour du fichier si fourni
        if (fichier != null && !fichier.isEmpty()) {
            validateFile(fichier);
            try {
                project.setFichierData(fichier.getBytes());
                project.setFichierNom(fichier.getOriginalFilename());
                project.setFichierType(fichier.getContentType());
                project.setFichierTaille(fichier.getSize());
                // Si c'est un projet CODE_FICHIER, on met à jour aussi la spécificationContenu avec le nom du nouveau fichier
                if (project.getSpecificationType() == SpecificationType.CODE_FICHIER) {
                    project.setSpecificationContenu(fichier.getOriginalFilename());
                }
            } catch (IOException e) {
                log.error("Erreur lors de la mise à jour du fichier", e);
                throw new BadRequestException("Erreur lors de la lecture du fichier");
            }
        }

        Project updatedProject = projectRepository.save(project);
        log.info("Projet modifié: {}", id);

        return mapToResponse(updatedProject);
    }

    /**
     * Supprime un projet et son fichier si applicable (seul le propriétaire peut le faire)
     */
    public void deleteProject(UUID id, UUID ownerId) {
        Project project = projectRepository.findByIdAndOwnerId(id, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Projet non trouvé ou accès refusé"));

        projectRepository.delete(project);
        log.info("Projet supprimé: {}", id);
    }

    /**
     * Ajoute un membre (testeur) à un projet
     */
    public void addMember(UUID projectId, UUID userId, UUID ownerId) {
        Project project = projectRepository.findByIdAndOwnerId(projectId, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Projet non trouvé ou accès refusé"));

        User member = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur non trouvé"));

        if (project.getMembers() == null) {
            project.setMembers(new ArrayList<>());
        }

        if (!project.getMembers().contains(member)) {
            project.getMembers().add(member);
            projectRepository.save(project);
            log.info("Membre ajouté au projet: {} - {}", projectId, userId);
        }
    }

    /**
     * Valide un fichier uploadé
     */
    private void validateFile(MultipartFile file) {
        // Vérifier la taille
        long maxSize = 50 * 1024 * 1024; // 50 MB
        if (file.getSize() > maxSize) {
            throw new BadRequestException("Le fichier dépasse la taille maximale de 50MB");
        }

        // Vérifier l'extension
        String filename = file.getOriginalFilename();
        if (filename == null || !hasAllowedExtension(filename)) {
            throw new BadRequestException("Type de fichier non autorisé. Autorisés: " + String.join(", ", ALLOWED_EXTENSIONS));
        }
    }

    /**
     * Vérifie si l'extension est autorisée
     */
    private boolean hasAllowedExtension(String filename) {
        String extension = filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
        return ALLOWED_EXTENSIONS.contains(extension);
    }

    /**
     * Writes fichierData bytes from DB to a temp file and returns a file:// URL for Selenium.
     */
    private String writeDbBytesToTempFileUrl(Project project) {
        byte[] data = project.getFichierData();
        if (data == null || data.length == 0) {
            throw new BadRequestException("Aucune donnée de fichier trouvée en base pour ce projet");
        }
        try {
            String ext = "";
            String nom = project.getFichierNom();
            if (nom != null && nom.contains(".")) {
                ext = nom.substring(nom.lastIndexOf("."));
            }
            Path tempFile = Files.createTempFile("selenium_", ext);
            Files.write(tempFile, data);
            tempFile.toFile().deleteOnExit();
            log.debug("Fichier temp créé pour Selenium: {}", tempFile);
            return tempFile.toAbsolutePath().normalize().toUri().toString();
        } catch (IOException e) {
            log.error("Erreur écriture fichier temp pour Selenium", e);
            throw new BadRequestException("Erreur lors de la préparation du fichier pour Selenium");
        }
    }

    /**
     * Convertit une entité Project en DTO ProjectResponse
     */
    private ProjectResponse mapToResponse(Project project) {
        return ProjectResponse.builder()
                .id(project.getId())
                .nom(project.getNom())
                .description(project.getDescription())
                .specificationType(project.getSpecificationType())
                .specificationContenu(project.getSpecificationContenu())
                .focusOptionnel(project.getFocusOptionnel())
                .statut(project.getStatut())
                .ownerName(project.getOwner().getNom())
                .ownerEmail(project.getOwner().getEmail())
                .nombreScripts(0) // À mettre à jour quand TestScript sera utilisé
                .fichierGenere(project.getFichierGenere())
                .executionLogs(project.getExecutionLogs())
                .createdAt(project.getCreatedAt())
                .build();
    }

    /**
     * Récupère les scénarios persistés d'un projet
     */
    public List<Map<String, Object>> getProjectScenarios(UUID id, UUID ownerId) {
        Project project = projectRepository.findByIdAndOwnerId(id, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Projet non trouvé ou accès refusé"));

        List<TestScript> scripts = testScriptRepository.findByProjectId(id);
        if (scripts.isEmpty()) {
            return new ArrayList<>();
        }

        // On prend le script le plus récent (si la génération a été lancée plusieurs fois)
        TestScript latestScript = scripts.stream()
                .max(Comparator.comparing(TestScript::getCreatedAt))
                .orElse(scripts.get(0));

        String scenariosJson = latestScript.getScenarios();
        List<Map<String, Object>> parsedScenarios = new ArrayList<>();
        if (scenariosJson != null && !scenariosJson.isEmpty() && !scenariosJson.equals("[]")) {
            try {
                parsedScenarios = objectMapper.readValue(scenariosJson, new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
            } catch (Exception e) {
                log.error("Erreur de parsing des scénarios sauvegardés: ", e);
            }
        }

        if (project.getSpecificationType() == SpecificationType.LIEN_APPLICATION) {
            List<Map<String, Object>> resultList = new ArrayList<>();

            // 1. Ajouter chaque scénario Gherkin ML individuellement (Random Forest)
            if (!parsedScenarios.isEmpty()) {
                for (Map<String, Object> sc : parsedScenarios) {
                    Map<String, Object> scenarioMap = new HashMap<>(sc);
                    // S'assurer que les champs attendus par le frontend existent
                    scenarioMap.putIfAbsent("type", "Fonctionnel");
                    scenarioMap.put("elementsCount", latestScript.getElementsCount());
                    resultList.add(scenarioMap);
                }
            }

            return resultList;
        }

        return parsedScenarios;
    }

    /**
     * Appelle l'API IA (Python) pour générer les scénarios Gherkin
     */
    public Map<String, Object> generateTestsForProject(UUID id, UUID ownerId) {
        Project project = projectRepository.findByIdAndOwnerId(id, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Projet non trouvé ou accès refusé"));

        if (project.getSpecificationType() != SpecificationType.USER_STORY
                && project.getSpecificationType() != SpecificationType.LIEN_APPLICATION
                && project.getSpecificationType() != SpecificationType.CODE_FICHIER) {
            throw new BadRequestException("La génération IA n'est supportée que pour le type USER_STORY, LIEN_APPLICATION ou CODE_FICHIER actuellement.");
        }

        String specificationContenu = project.getSpecificationContenu();
        if (specificationContenu == null || specificationContenu.isEmpty()) {
            throw new BadRequestException("Le contenu est vide (User Story ou URL).");
        }

        project.setStatut(ProjectStatus.EN_COURS);
        projectRepository.save(project);

        try {
            List<Map<String, String>> elementsInteractifs = new ArrayList<>();
            // -- PARTIE SELENIUM CRAWL (SI LIEN_APPLICATION) --
            if (project.getSpecificationType() == SpecificationType.LIEN_APPLICATION || project.getSpecificationType() == SpecificationType.CODE_FICHIER) {
                String targetUrl = project.getSpecificationType() == SpecificationType.CODE_FICHIER
                        ? writeDbBytesToTempFileUrl(project)
                        : specificationContenu;

                log.info("🔍 Lancement de Selenium en mode Headless pour crawler : {}", specificationContenu);

                ChromeOptions options = new ChromeOptions();
                options.addArguments("--headless=new");
                options.addArguments("--disable-gpu");
                options.addArguments("--window-size=1920,1080");
                options.addArguments("--remote-allow-origins=*");
                options.addArguments("--no-sandbox");
                options.addArguments("--disable-dev-shm-usage");
                // Éviter la détection anti-bot basique
                options.addArguments("--disable-blink-features=AutomationControlled");
                options.setExperimentalOption("excludeSwitches", java.util.Arrays.asList("enable-automation"));

                WebDriver driver = null;
                try {
                    driver = new ChromeDriver(options);
                    driver.get(targetUrl);

                    // ===== ENHANCED: Use improved crawling with Angular detection and retry logic =====
                    SeleniumCrawlerHelper.waitForPageReady(driver);

                    // Retry crawling up to 3 times with escalating patience
                    elementsInteractifs = SeleniumCrawlerHelper.crawlElementsWithRetry(driver, 3);

                    log.info("✓ Crawling terminé : {} éléments interactifs trouvés.", elementsInteractifs.size());

                } catch (Exception e) {
                    log.error("❌ Erreur lors du crawling Selenium : {}", e.getMessage());
                    // Ne pas crasher : on continue avec une liste vide (le fallback s'en occupera)
                    log.warn("Crawling échoué, utilisation du fallback scénarios génériques.");
                } finally {
                    if (driver != null) {
                        try { driver.quit(); } catch (Exception ignored) {}
                    }
                }
            }

            // -- FIN SELENIUM --

            // ===== FALLBACK CRITIQUE : Si 0 éléments crawlés, générer des scénarios génériques =====
            // (évite le 400 de Python et permet quand même de produire un résultat)
            if ((project.getSpecificationType() == SpecificationType.LIEN_APPLICATION || project.getSpecificationType() == SpecificationType.CODE_FICHIER)
                    && elementsInteractifs.isEmpty()) {
                log.warn("Aucun élément détecté pour {}. Génération de scénarios de navigation génériques.", specificationContenu);
                
                List<Map<String, Object>> fallbackScenarios = new ArrayList<>();
                fallbackScenarios.add(createScenario(
                    "Navigation - Page principale",
                    "Vérifier que la page principale se charge correctement",
                    "La page s'affiche sans erreur HTTP",
                    "Fonctionnel",
                    "Feature: Navigation\n  Scenario: Charger la page principale\n    Given l'utilisateur ouvre un navigateur\n    When il navigue vers \"" + specificationContenu + "\"\n    Then la page doit se charger sans erreur 404 ou 500"
                ));
                fallbackScenarios.add(createScenario(
                    "Navigation - Liens fonctionnels",
                    "Vérifier que les liens de la page fonctionnent",
                    "Aucun lien cassé (404) sur la page principale",
                    "Fonctionnel",
                    "Feature: Liens\n  Scenario: Vérifier les liens de navigation\n    Given l'utilisateur est sur la page \"" + specificationContenu + "\"\n    When il clique sur les liens de navigation\n    Then chaque lien doit ouvrir une page valide"
                ));
                fallbackScenarios.add(createScenario(
                    "Performance - Temps de chargement",
                    "Vérifier que la page charge en moins de 5 secondes",
                    "Temps de chargement < 5s",
                    "Performance",
                    "Feature: Performance\n  Scenario: Chargement rapide\n    Given l'utilisateur accède à \"" + specificationContenu + "\"\n    When le chargement est mesuré\n    Then la page doit charger en moins de 5 secondes"
                ));
                fallbackScenarios.add(createScenario(
                    "Accessibilité - Contenu visible",
                    "Vérifier que du contenu textuel est visible sur la page",
                    "Du texte est affiché dans le corps de la page",
                    "Fonctionnel",
                    "Feature: Accessibilité\n  Scenario: Contenu lisible\n    Given l'utilisateur visite \"" + specificationContenu + "\"\n    When la page est complètement chargée\n    Then un contenu textuel doit être visible à l'écran"
                ));

                // Sauvegarder en base
                TestScript fallbackScript = TestScript.builder()
                        .project(project)
                        .scriptContent("# Scénarios génériques (page sans éléments interactifs détectables)\n# URL: " + specificationContenu)
                        .framework("selenium-ml-fallback")
                        .statut("GENERE_FALLBACK")
                        .elementsCount(0)
                        .build();
                fallbackScript.setScenarios(objectMapper.writeValueAsString(fallbackScenarios));
                testScriptRepository.save(fallbackScript);
                
                project.setStatut(ProjectStatus.TERMINE);
                projectRepository.save(project);
                
                Map<String, Object> finalResponse = new HashMap<>();
                finalResponse.put("message", "⚠️ Aucun élément interactif détecté sur la page (site protégé ou SPA). " +
                        "Des scénarios de navigation génériques ont été générés.");
                finalResponse.put("scenarios", fallbackScenarios);
                finalResponse.put("elementsCount", 0);
                return finalResponse;
            }

            // Timeout de 30 minutes pour crawl + validation locators + génération IA.
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(60000);
            requestFactory.setReadTimeout(1800000); 
            RestTemplate restTemplate = new RestTemplate(requestFactory);
            
            String pythonApiUrl = "";
            Map<String, String> requestBody = new HashMap<>();
            
            if (project.getSpecificationType() == SpecificationType.USER_STORY) {
                pythonApiUrl = "http://127.0.0.1:8000/api/generate-gherkin";
                requestBody.put("user_story", specificationContenu);
                
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                
                HttpEntity<Map<String, String>> request = new HttpEntity<>(requestBody, headers);
                
                log.info("Appel de l'API IA Python ({}) pour le projet {}", pythonApiUrl, id);
                ResponseEntity<String> response = restTemplate.postForEntity(pythonApiUrl, request, String.class);
                
                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    Map<String, Object> result = objectMapper.readValue(response.getBody(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                    
                    List<Map<String, String>> scenariosObj = (List<Map<String, String>>) result.get("scenarios");
                    
                    // Enregistrer en base
                    TestScript testScript = TestScript.builder()
                            .project(project)
                            .scriptContent("Générations des scénarios terminées")
                            .framework("cucumber")
                            .statut("GENERE")
                            .elementsCount(scenariosObj != null ? scenariosObj.size() : 0)
                            .build();
                    
                    testScript.setScenarios(objectMapper.writeValueAsString(scenariosObj));
                    testScriptRepository.save(testScript);
                    
                    project.setStatut(ProjectStatus.TERMINE);
                    projectRepository.save(project);
                    
                    Map<String, Object> finalResponse = new HashMap<>();
                    finalResponse.put("message", "Génération terminée avec succès");
                    finalResponse.put("scenarios", scenariosObj);
                    
                    return finalResponse;
                } else {
                    throw new RuntimeException("Erreur de l'API IA");
                }
            } else if (project.getSpecificationType() == SpecificationType.LIEN_APPLICATION || project.getSpecificationType() == SpecificationType.CODE_FICHIER) {
                // Appel ML Python /api/analyze-app
                pythonApiUrl = "http://127.0.0.1:8000/api/analyze-app";
                
                Map<String, Object> analyzePayload = new HashMap<>();
                analyzePayload.put("url", project.getSpecificationType() == SpecificationType.CODE_FICHIER ? writeDbBytesToTempFileUrl(project) : specificationContenu);
                analyzePayload.put("elements", elementsInteractifs);
                analyzePayload.put("focusObjective", project.getFocusOptionnel());

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(analyzePayload, headers);
                
                log.info("Envoi de {} éléments interactifs à l'IA Python pour classification ML...", elementsInteractifs.size());
                ResponseEntity<String> response = restTemplate.postForEntity(pythonApiUrl, requestEntity, String.class);
                
                if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                    Map<String, Object> result = objectMapper.readValue(response.getBody(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                    
                    List<Map<String, String>> scenariosObj = (List<Map<String, String>>) result.get("scenarios");
                    
                    String elementsJson = "";
                    try {
                        elementsJson = (String) result.get("python_script");
                        if (elementsJson == null || elementsJson.isEmpty() || elementsJson.startsWith("# Erreur") || elementsJson.startsWith("# CodeT5 n'est pas")) {
                            elementsJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(elementsInteractifs);
                        }
                    } catch(Exception e) {
                        elementsJson = "[]";
                    }

                    int reliableElementsCount = elementsInteractifs != null ? elementsInteractifs.size() : 0;
                    Object locatorValidationObj = result.get("locator_validation");
                    if (locatorValidationObj instanceof Map<?, ?> locatorValidationMap) {
                        Object reliableObj = locatorValidationMap.get("reliable");
                        if (reliableObj instanceof Number reliableNumber) {
                            reliableElementsCount = reliableNumber.intValue();
                        }
                    }

                    TestScript testScript = TestScript.builder()
                            .project(project)
                            .scriptContent(elementsJson) 
                            .framework("selenium-ml")
                            .statut("CRAWLED_AND_ANALYZED")
                            .elementsCount(reliableElementsCount)
                            .build();
                    
                    testScript.setScenarios(objectMapper.writeValueAsString(scenariosObj));
                    testScriptRepository.save(testScript);
                    
                    project.setStatut(ProjectStatus.TERMINE);
                    projectRepository.save(project);
                    
                    Map<String, Object> finalResponse = new HashMap<>();
                    finalResponse.put("message", result.get("message"));

                    List<Map<String, Object>> displayList = new ArrayList<>();
                    if (scenariosObj != null && !scenariosObj.isEmpty()) {
                        for (Map<String, String> sc : scenariosObj) {
                            Map<String, Object> scenarioMap = new HashMap<>(sc);
                            scenarioMap.putIfAbsent("type", "Fonctionnel");
                            displayList.add(scenarioMap);
                        }
                    }

                    finalResponse.put("scenarios", displayList);
                    finalResponse.put("elementsCount", testScript.getElementsCount());
                    finalResponse.put("elements_classified", result.get("elements_classified"));
                    finalResponse.put("locator_validation", result.get("locator_validation"));
                    finalResponse.put("focusObjective", project.getFocusOptionnel());
                    
                    return finalResponse;
                } else {
                    throw new RuntimeException("Erreur de l'API IA ML Python");
                }
            }
            return null;
        } catch (Exception e) {
            log.error("Erreur gherkin/crawling generator: ", e);
            project.setStatut(ProjectStatus.ERREUR);
            projectRepository.save(project);
            throw new RuntimeException("Erreur lors de la communication avec l'IA: " + e.getMessage());
        }
    }

    /**
     * Lance l'exécution des tests générés
     */
    public Map<String, Object> runTests(UUID id, UUID ownerId, List<String> selectedScenarioIds) {
        Project project = projectRepository.findByIdAndOwnerId(id, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Projet non trouvé ou accès refusé"));

        try {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(60000);
            requestFactory.setReadTimeout(3600000); // 1 heure
            RestTemplate restTemplate = new RestTemplate(requestFactory);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            if (project.getSpecificationType() == SpecificationType.LIEN_APPLICATION || project.getSpecificationType() == SpecificationType.CODE_FICHIER) {
                List<TestScript> scripts = testScriptRepository.findByProjectId(id);
                if (scripts == null || scripts.isEmpty()) {
                    throw new ResourceNotFoundException("Aucun script Selenium trouvé pour ce projet");
                }
                TestScript script = scripts.get(0);

                String scenariosJson = script.getScenarios();
                if (scenariosJson == null || scenariosJson.isEmpty()) {
                    throw new RuntimeException("Aucun scénario trouvé pour ce projet");
                }

                List<Map<String, Object>> allScenarios = objectMapper.readValue(scenariosJson,
                    new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});

                List<Map<String, Object>> selectedScenarios = allScenarios;
                if (selectedScenarioIds != null && !selectedScenarioIds.isEmpty()) {
                    selectedScenarios = allScenarios.stream()
                        .filter(s -> selectedScenarioIds.contains(s.get("nomSenario").toString()))
                        .collect(java.util.stream.Collectors.toList());
                }

                if (selectedScenarios.isEmpty()) {
                    throw new BadRequestException("Aucun scénario sélectionné pour l'exécution");
                }

                Map<String, Object> executeRequest = new HashMap<>();
                executeRequest.put("projectId", id.toString());

                String urlToTest = project.getSpecificationContenu();
                if (project.getSpecificationType() == SpecificationType.CODE_FICHIER) {
                    urlToTest = writeDbBytesToTempFileUrl(project);
                }

                executeRequest.put("url", urlToTest);
                executeRequest.put("focusObjective", project.getFocusOptionnel());

                List<Map<String, Object>> scenarios = new ArrayList<>();
                for (Map<String, Object> scenario : selectedScenarios) {
                    Map<String, Object> scenarioItem = new HashMap<>();
                    scenarioItem.put("nomSenario", scenario.get("nomSenario"));
                    scenarioItem.put("senario", scenario.get("senario"));
                    scenarioItem.put("selected", true);
                    scenarios.add(scenarioItem);
                }
                executeRequest.put("scenarios", scenarios);

                String pythonApiUrl = "http://127.0.0.1:8000/api/execute-scenarios";
                HttpEntity<Map<String, Object>> request = new HttpEntity<>(executeRequest, headers);

                log.info("🚀 Lancement exécution Phase 3 pour projet {}", id);
                ResponseEntity<String> response = restTemplate.postForEntity(pythonApiUrl, request, String.class);

                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    Map<String, Object> executionResult = objectMapper.readValue(response.getBody(),
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});

                    Object summaryObj = executionResult.get("summary");
                    Map<String, Object> summary = summaryObj instanceof Map
                        ? (Map<String, Object>) summaryObj
                        : new HashMap<>();

                    long durationMs = 0L;
                    Object durationObj = executionResult.get("duration_ms");
                    if (durationObj instanceof Number) {
                        durationMs = ((Number) durationObj).longValue();
                    }

                    String scenarioResultsJson = null;
                    Object scenariosResultsObj = executionResult.get("scenarios_results");
                    if (scenariosResultsObj != null) {
                        scenarioResultsJson = objectMapper.writeValueAsString(scenariosResultsObj);
                    }

                    String summaryJson = objectMapper.writeValueAsString(summary);
                    String logsJson = null;
                    Object executionLogsObj = executionResult.get("execution_logs");
                    if (executionLogsObj != null) {
                        logsJson = objectMapper.writeValueAsString(executionLogsObj);
                    }

                    TestExecutionResult result = TestExecutionResult.builder()
                        .testScript(script)
                        .project(project)
                        .status((String) executionResult.getOrDefault("status", "COMPLETED"))
                        .executionDurationMs(durationMs)
                        .scenarioResults(scenarioResultsJson)
                        .assertionResults(summaryJson)
                        .reportPdfBlob(decodePdfIfPresent(executionResult))
                        .logs(logsJson)
                        .errorDetails(null)
                        .executedBy(SecurityContextHolder.getContext().getAuthentication().getName())
                        .build();

                    TestExecutionResult savedResult = testExecutionResultRepository.save(result);
                    log.info("[OK] Execution results saved to database: {}", savedResult.getId());
                    executionResult.put("resultId", savedResult.getId().toString());

                    project.setStatut(ProjectStatus.TERMINE);
                    projectRepository.save(project);

                    return executionResult;
                } else {
                    throw new RuntimeException("Erreur lors de l'exécution sur l'API Python Phase 3");
                }
            } else {
                String fichierGenere = project.getFichierGenere();
                if (fichierGenere == null || fichierGenere.isEmpty()) {
                    throw new BadRequestException("Aucun fichier de test généré pour ce projet.");
                }
                String pythonApiUrl = "http://127.0.0.1:8000/api/run-tests";
                Map<String, String> requestBody = new HashMap<>();
                requestBody.put("fichier", fichierGenere);

                HttpEntity<Map<String, String>> request = new HttpEntity<>(requestBody, headers);
                log.info("Lancement exécution tests BDD pour projet {}: fichier {}", id, fichierGenere);
                ResponseEntity<String> response = restTemplate.postForEntity(pythonApiUrl, request, String.class);

                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    return objectMapper.readValue(response.getBody(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                } else {
                    throw new RuntimeException("Erreur API Python BDD");
                }
            }
        } catch (Exception e) {
            log.error("Erreur runTests: ", e);
            throw new RuntimeException("Erreur d'exécution des tests: " + e.getMessage());
        }
    }

    private byte[] decodePdfIfPresent(Map<String, Object> executionResult) {
        Object pdfBase64 = executionResult.get("report_base64_pdf");
        if (pdfBase64 != null && pdfBase64 instanceof String) {
            try {
                return java.util.Base64.getDecoder().decode((String) pdfBase64);
            } catch (IllegalArgumentException e) {
                log.warn("Invalid base64 PDF");
                return null;
            }
        }
        return null;
    }

    /**
     * Get latest execution metrics for a project
     */
    public Map<String, Object> getExecutionMetrics(UUID projectId, UUID userId) {
        Project project = projectRepository.findByIdAndOwnerId(projectId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Projet non trouvé"));

        Map<String, Object> metrics = new HashMap<>();
        metrics.put("total", 0);
        metrics.put("passed", 0);
        metrics.put("failed", 0);
        metrics.put("lastExecutedAt", null);
        metrics.put("report_base64_pdf", null);
        metrics.put("lastExecutionResultId", null);
        metrics.put("hasPdfReport", false);

        try {
            List<TestExecutionResult> results = testExecutionResultRepository.findByProjectIdOrderByExecutedAtDesc(projectId);
            if (!results.isEmpty()) {
                TestExecutionResult latest = results.get(0);
                metrics.put("lastExecutionResultId", latest.getId());

                byte[] pdfBlob = latest.getReportPdfBlob();
                if (pdfBlob != null && pdfBlob.length > 0) {
                    metrics.put("report_base64_pdf", java.util.Base64.getEncoder().encodeToString(pdfBlob));
                    metrics.put("hasPdfReport", true);
                }

                String scenarioResultsJson = latest.getScenarioResults();
                if (scenarioResultsJson != null && !scenarioResultsJson.isEmpty()) {
                    List<Map<String, Object>> scenarioResults = objectMapper.readValue(scenarioResultsJson,
                        new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});

                    int passed = 0, failed = 0;
                    for (Map<String, Object> result : scenarioResults) {
                        String status = (String) result.get("status");
                        if ("PASSED".equals(status)) {
                            passed++;
                        } else if ("FAILED".equals(status) || "ERROR".equals(status)) {
                            failed++;
                        }
                    }
                    metrics.put("total", scenarioResults.size());
                    metrics.put("passed", passed);
                    metrics.put("failed", failed);
                    metrics.put("lastExecutedAt", latest.getExecutedAt());
                } else {
                    String summaryJson = latest.getAssertionResults();
                    if (summaryJson != null && !summaryJson.isEmpty()) {
                        Map<String, Object> summary = objectMapper.readValue(summaryJson,
                            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});

                        metrics.put("total", ((Number) summary.getOrDefault("total", 0)).intValue());
                        metrics.put("passed", ((Number) summary.getOrDefault("passed", 0)).intValue());
                        metrics.put("failed", ((Number) summary.getOrDefault("failed", 0)).intValue());
                        metrics.put("lastExecutedAt", latest.getExecutedAt());
                    }
                }
                metrics.put("status", latest.getStatus());
            }
        } catch (Exception e) {
            log.warn("Could not retrieve execution metrics: {}", e.getMessage());
        }

        return metrics;
    }

    public Map<String, Object> getFileContent(UUID projectId, UUID userId) {
        Project project = projectRepository.findByIdAndOwnerId(projectId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Projet non trouvé ou accès refusé"));

        byte[] data = project.getFichierData();
        if (data == null || data.length == 0) {
            throw new BadRequestException("Aucun fichier trouvé");
        }

        Map<String, Object> result = new HashMap<>();
        result.put("filename", project.getFichierNom());
        result.put("content", java.util.Base64.getEncoder().encodeToString(data));
        result.put("contentType", project.getFichierType());
        result.put("size", data.length);
        return result;
    }

    public Map<String, Object> analyzeHtmlProxy(UUID projectId, String htmlContent, UUID userId) {
        Project project = projectRepository.findByIdAndOwnerId(projectId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Projet non trouvé"));

        String pythonApiUrl = "http://127.0.0.1:8000/api/analyze-html";
        
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setReadTimeout(60000);
        RestTemplate restTemplate = new RestTemplate(requestFactory);

        Map<String, String> payload = new HashMap<>();
        payload.put("html_content", htmlContent);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, String>> request = new HttpEntity<>(payload, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(pythonApiUrl, request, String.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, Object> result = objectMapper.readValue(response.getBody(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                return result;
            }
            throw new RuntimeException("Erreur API Python Analyse");
        } catch (Exception e) {
            log.error("Error analyzeHtmlProxy: ", e);
            throw new RuntimeException("Erreur lors de l'analyse : " + e.getMessage());
        }
    }

    public Map<String, Object> generateFileSeleniumProxy(UUID projectId, Map<String, Object> body, UUID userId) {
        Project project = projectRepository.findByIdAndOwnerId(projectId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Projet non trouvé"));

        String pythonApiUrl = "http://127.0.0.1:8000/api/generate-file-selenium";
        
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setReadTimeout(120000);
        RestTemplate restTemplate = new RestTemplate(requestFactory);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(pythonApiUrl, request, String.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, Object> result = objectMapper.readValue(response.getBody(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                
                String seleniumCode = (String) result.get("selenium_code");
                if (seleniumCode != null) {
                    List<TestScript> scripts = testScriptRepository.findByProjectId(projectId);
                    TestScript script;
                    if (scripts.isEmpty()) {
                        script = TestScript.builder()
                                .project(project)
                                .framework("selenium-codet5")
                                .statut("GENERE")
                                .createdAt(LocalDateTime.now())
                                .build();
                    } else {
                        script = scripts.get(0);
                    }
                    script.setScriptContent(seleniumCode);
                    if (body.containsKey("tests")) {
                        script.setScenarios(objectMapper.writeValueAsString(body.get("tests")));
                    }
                    testScriptRepository.save(script);
                }
                return result;
            }
            throw new RuntimeException("Erreur API Python Génération Script");
        } catch (Exception e) {
            log.error("Error generateFileSeleniumProxy: ", e);
            throw new RuntimeException("Erreur lors de la génération : " + e.getMessage());
        }
    }

    public Map<String, Object> runFileSeleniumProxy(UUID projectId, Map<String, Object> body, UUID userId) {
        Project project = projectRepository.findByIdAndOwnerId(projectId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Projet non trouvé"));

        String pythonApiUrl = "http://127.0.0.1:8000/api/run-file-selenium";
        
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setReadTimeout(3600000);   // 1 heure
        requestFactory.setConnectTimeout(60000);  // 1 minute
        RestTemplate restTemplate = new RestTemplate(requestFactory);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(pythonApiUrl, request, String.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, Object> result = objectMapper.readValue(response.getBody(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                
                TestScript script = null;
                try {
                    script = getScriptByProject(projectId, userId);
                } catch (Exception e) {
                    log.warn("Aucun script trouvé");
                }

                if (script == null) {
                    Object scriptCode = body.get("script_code");
                    String scriptContent = scriptCode != null && !scriptCode.toString().isBlank()
                            ? scriptCode.toString()
                            : "# Script Selenium execute sans generation persistante";
                    script = TestScript.builder()
                            .project(project)
                            .scriptContent(scriptContent)
                            .framework("selenium-codet5")
                            .statut("GENERE_EXECUTION")
                            .createdAt(LocalDateTime.now())
                            .build();
                    if (body.containsKey("tests")) {
                        try {
                            script.setScenarios(objectMapper.writeValueAsString(body.get("tests")));
                        } catch (Exception e) {
                            log.warn("Could not serialize tests for fallback script");
                        }
                    }
                    script = testScriptRepository.save(script);
                }

                TestExecutionResult executionResult = TestExecutionResult.builder()
                        .project(project)
                        .testScript(script) 
                        .status((String) result.getOrDefault("status", "COMPLETED"))
                        .executedAt(LocalDateTime.now())
                        .logs((String) result.getOrDefault("logs", ""))
                        .executedBy(SecurityContextHolder.getContext().getAuthentication().getName())
                        .build();

                String pdfBase64 = (String) result.get("pdf_base64");
                if (pdfBase64 == null) {
                    pdfBase64 = (String) result.get("report_base64_pdf");
                }
                if (pdfBase64 != null) {
                    executionResult.setReportPdfBlob(java.util.Base64.getDecoder().decode(pdfBase64));
                    result.put("pdf_base64", pdfBase64);
                    result.put("report_base64_pdf", pdfBase64);
                }

                if (result.get("results") != null) {
                    try {
                        String resultsJson = objectMapper.writeValueAsString(result.get("results"));
                        executionResult.setAssertionResults(resultsJson);
                    } catch (Exception e) {
                        log.warn("Could not serialize test results");
                    }
                }

                Object scenarioResults = result.get("scenarios_results");
                if (scenarioResults == null) {
                    scenarioResults = result.get("scenario_results");
                }
                if (scenarioResults != null) {
                    try {
                        executionResult.setScenarioResults(objectMapper.writeValueAsString(scenarioResults));
                    } catch (Exception e) {
                        log.warn("Could not serialize scenario results");
                    }
                }

                testExecutionResultRepository.save(executionResult);
                return result;
            }
            throw new RuntimeException("Erreur API Python Exécution");
        } catch (Exception e) {
            log.error("Error runFileSeleniumProxy: ", e);
            throw new RuntimeException("Erreur lors de l'exécution : " + e.getMessage());
        }
    }

    public java.util.Optional<TestExecutionResult> getTestExecutionResult(UUID resultId) {
        return testExecutionResultRepository.findById(resultId);
    }

    public TestScript getScriptByProject(UUID projectId, UUID userId) {
        List<TestScript> scripts = testScriptRepository.findByProjectId(projectId);
        if (scripts.isEmpty()) {
            throw new ResourceNotFoundException("Aucun script trouvé");
        }
        return scripts.get(0);
    }

    private Map<String, Object> createScenario(String nom, String description, String resultatAttendu, String type, String gherkin) {
        Map<String, Object> sc = new HashMap<>();
        sc.put("nomSenario", nom);
        sc.put("description", description);
        sc.put("resultatAttendu", resultatAttendu);
        sc.put("type", type);
        sc.put("senario", gherkin);
        return sc;
    }

    /**
     * Met à jour les scénarios d'un projet
     */
    public void updateProjectScenarios(UUID id, UUID ownerId, List<Map<String, Object>> scenarios) {
        Project project = projectRepository.findByIdAndOwnerId(id, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Projet non trouvé ou accès refusé"));

        List<TestScript> scripts = testScriptRepository.findByProjectId(id);
        if (scripts.isEmpty()) {
            throw new ResourceNotFoundException("Aucun script trouvé pour ce projet");
        }

        // On prend le script le plus récent
        TestScript latestScript = scripts.stream()
                .max(Comparator.comparing(TestScript::getCreatedAt))
                .orElse(scripts.get(0));

        try {
            latestScript.setScenarios(objectMapper.writeValueAsString(scenarios));
            testScriptRepository.save(latestScript);
            log.info("Scénarios mis à jour pour le projet: {}", id);
        } catch (Exception e) {
            log.error("Erreur lors de la mise à jour des scénarios: ", e);
            throw new RuntimeException("Erreur de sauvegarde des scénarios");
        }
    }
}
