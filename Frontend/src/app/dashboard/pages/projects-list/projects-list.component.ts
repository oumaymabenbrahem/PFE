import { Component, OnInit, OnDestroy } from '@angular/core';
import { ProjectService } from '../../../core/services/project.service';
import { ProjectResponse } from '../../../shared/models/project.model';
import { Router, ActivatedRoute } from '@angular/router';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import { JiraService } from '../../../core/services/jira.service';

@Component({
  selector: 'app-projects-list',
  templateUrl: './projects-list.component.html',
  styleUrls: ['./projects-list.component.scss']
})
export class ProjectsListComponent implements OnInit, OnDestroy {
  projects: ProjectResponse[] = [];
  isLoading = true;
  errorMessage = '';
  successMessage = '';
  generatingId: string | null = null;
  selectedUserStoryProject: ProjectResponse | null = null;
  selectedProjectForDetails: ProjectResponse | null = null;  
  generatedScenarios: any[] | null = null;
  isJiraConnected = false;
  jiraProjectKey = '';
  jiraUserStoryId = '';
  isPushingToJira = false;
  pushMessage = '';
  pushError = '';
  
  // Modale de confirmation de suppression
  showDeleteConfirmation = false;
  projectToDelete: ProjectResponse | null = null;
  isDeleting = false;
  
  // Terminal UI
  selectedTerminalProject: ProjectResponse | null = null;
  terminalSteps: any[] = [];
  terminalProgress: number = 0;
  terminalInterval: any;
  terminalTab: 'terminal' | 'scripts' | 'results' = 'terminal';
  terminalScripts: any[] = [];
  testResults: any = null;
  isExecutingTests: boolean = false;
  
  // Web Testing Dashboard UI
  showWebTestingDashboard: boolean = false;
  webTestingProject: ProjectResponse | null = null;
  pipelineStatus: 'generating' | 'completed' | 'error' = 'generating';
  pipelineCurrentStep: number = 0;
  pipelineProgress: number = 0;
  elementsDetected: number | string = '-';
  scenariosGenerated: number | string = '-';
  passedMetrics: string = '-';
  failedMetrics: string = '-';
  pipelineInterval: any;
  executionPdfReport?: string;
  activeFileSpecTab: 'analyser' | 'generer' | 'tester' | 'historique' = 'analyser';
  
  // File analysis (BeautifulSoup)
  fileAnalysisFields: any[] = [];
  fileAnalysisBehaviors: any[] = [];
  isAnalyzingFile: boolean = false;
  fileAnalysisError: string = '';
  fileAnalysisSummary: any = null;
  
  private readonly executionMetricsStoragePrefix = 'execution_metrics_';

  private destroy$ = new Subject<void>();

  constructor(
    private projectService: ProjectService,
    private jiraService: JiraService,
    private router: Router,
    private route: ActivatedRoute
  ) {}

  setFileSpecTab(tab: 'analyser' | 'generer' | 'tester' | 'historique'): void {
    this.activeFileSpecTab = tab;
  }

  ngOnInit(): void {
    this.loadProjects();
    this.checkJiraStatus();

    this.route.queryParams.pipe(takeUntil(this.destroy$)).subscribe(params => {
      if (params['jira'] === 'success') {
        this.successMessage = "Connecté à Jira avec succès !";
        this.isJiraConnected = true;
        setTimeout(() => this.successMessage = '', 6000);
      } else if (params['jira'] === 'error') {
        this.errorMessage = "Échec de la connexion à Jira.";
        setTimeout(() => this.errorMessage = '', 6000);
      }
    });
  }

  checkJiraStatus(): void {
    this.jiraService.getStatus().pipe(takeUntil(this.destroy$)).subscribe({
      next: (res) => this.isJiraConnected = res.connected,
      error: () => this.isJiraConnected = false
    });
  }

  connectJira(): void {
    this.jiraService.getLoginUrl().pipe(takeUntil(this.destroy$)).subscribe({
      next: (res) => {
        window.location.href = res.url;
      },
      error: (err) => {
        this.errorMessage = "Impossible de récupérer l'URL d'autorisation Jira.";
      }
    });
  }

  pushToJira(): void {
    if (!this.generatedScenarios || this.generatedScenarios.length === 0) return;
    
    this.isPushingToJira = true;
    this.pushMessage = '';
    this.pushError = '';

    this.jiraService.pushTests(this.jiraProjectKey, this.jiraUserStoryId, this.generatedScenarios)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (res) => {
          this.isPushingToJira = false;
          this.pushMessage = res?.message || "Tests poussés vers Jira/Xray avec succès !";
          setTimeout(() => this.pushMessage = '', 7000);
        },
        error: (err) => {
          this.isPushingToJira = false;
          console.error("Jira Push Error:", err);
          this.pushError = err.error?.message || err.message || "Erreur lors du transfert vers Jira.";
          setTimeout(() => this.pushError = '', 8000);
        }
      });
  }

  loadProjects(): void {
    this.isLoading = true;
    this.errorMessage = '';
    this.successMessage = '';

    this.projectService.getMyProjects()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (data: ProjectResponse[]) => {
          // Trier les projets par date décroissante (plus récents en premier)
          this.projects = data.sort((a, b) => {
            const dateA = new Date(a.createdAt).getTime();
            const dateB = new Date(b.createdAt).getTime();
            return dateB - dateA; // Ordre décroissant
          });
          this.isLoading = false;
        },
        error: (error) => {
          console.error('Erreur lors du chargement des projets:', error);
          this.errorMessage = 'Erreur lors du chargement des projets. Veuillez réessayer.';
          this.isLoading = false;
        }
      });
  }

  viewProject(projectId: string): void {
    this.router.navigate(['/project-details', projectId]);
  }

  editProject(projectId: string): void {
    this.router.navigate(['/project-edit', projectId]);
  }

  deleteProject(projectId: string): void {
    // Trouver le projet pour afficher ses infos dans la modale
    const project = this.projects.find(p => p.id === projectId);
    if (project) {
      this.projectToDelete = project;
      this.showDeleteConfirmation = true;
      document.body.style.overflow = 'hidden';
    }
  }

  /**
   * Confirme et exécute la suppression du projet
   */
  confirmDelete(): void {
    if (!this.projectToDelete) return;

    this.isDeleting = true;
    this.projectService.deleteProject(this.projectToDelete.id)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.isDeleting = false;
          this.successMessage = 'Projet supprimé avec succès!';
          this.closeDeleteConfirmation();
          this.loadProjects(); // Recharge et réapplique le tri
          setTimeout(() => this.successMessage = '', 3000);
        },
        error: (error) => {
          this.isDeleting = false;
          console.error('Erreur lors de la suppression:', error);
          this.errorMessage = 'Erreur lors de la suppression du projet.';
        }
      });
  }

  /**
   * Ferme la modale de confirmation de suppression
   */
  closeDeleteConfirmation(): void {
    this.showDeleteConfirmation = false;
    this.projectToDelete = null;
    this.isDeleting = false;
    document.body.style.overflow = '';
  }

  createNewProject(): void {
    this.router.navigate(['/upload-projet']);
  }

  clearMessage(type: 'error' | 'success'): void {
    if (type === 'error') {
      this.errorMessage = '';
    } else {
      this.successMessage = '';
    }
  }

  /**
   * Génère les tests Selenium pour un projet
   */
  onGenerateTests(projectId: string): void {
    const project = this.projects.find(p => p.id === projectId);
    
    if (project?.specificationType === 'LIEN_APPLICATION' || project?.specificationType === 'CODE_FICHIER') {
      this.openWebTestingDashboard(project);
    }
    
    this.generatingId = projectId;

    if (project) {
      project.statut = 'EN_COURS';
      if (project.specificationType === 'LIEN_APPLICATION') {
          this.successMessage = "🤖 Crawling en cours... Cette opération peut prendre jusqu'à 60 secondes.";
      } else if (project.specificationType === 'CODE_FICHIER') {
          this.successMessage = "🤖 Analyse du fichier en cours... Génération des tests en progression.";
      }
    }

    this.projectService.generateTests(projectId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          // Succès de la génération
          this.generatingId = null;
          this.generatedScenarios = (response.scenarios || []).filter((scenario: any) =>
            scenario?.type !== 'Selenium Script' && scenario?.nomSenario !== 'Script Selenium (CodeT5)'
          );
          if (project) {
            project.statut = 'TERMINE';
            const generatedCount = this.generatedScenarios ? this.generatedScenarios.length : 0;
            project.nombreScripts = (project.nombreScripts || 0) + (generatedCount || 1);
          }
          this.successMessage = `✅ Scénarios générés avec succès! Exécution Selenium...`;

          if (this.showWebTestingDashboard && project) {
             if (this.pipelineInterval) clearInterval(this.pipelineInterval);
             this.pipelineCurrentStep = 4; // Executing
             this.pipelineProgress = 80;
             this.pipelineStatus = 'generating'; // still active because executing
             this.elementsDetected = response.elementsCount !== undefined ? response.elementsCount : '-';
             if (response.elementsCount !== undefined && response.elementsCount !== null) {
               this.cacheElementsDetected(project.id, response.elementsCount);
             }
             this.scenariosGenerated = response.scenarios?.length || 0;

             // RESET metrics BEFORE execution starts
             this.passedMetrics = '0';
             this.failedMetrics = '0';

             // APpel de l'execution Selenium pure ! (Mode réel)
             this.projectService.runTests(project.id).subscribe({
                next: (execRes: any) => {
                   console.log('[DEBUG] Response received:', execRes);
                   this.pipelineCurrentStep = 5;
                   this.pipelineProgress = 100;
                   this.pipelineStatus = 'completed';

                   // Update metrics from summary
                   if (execRes.summary) {
                       this.passedMetrics = execRes.summary.passed?.toString() || '0';
                       this.failedMetrics = execRes.summary.failed?.toString() || '0';
                       this.scenariosGenerated = (execRes.summary.total ?? 0).toString();
                       this.cacheExecutionMetrics(project.id, execRes.summary);
                       console.log('[DEBUG] Metrics updated:', { passed: this.passedMetrics, failed: this.failedMetrics });
                   } else {
                       console.warn('[DEBUG] No summary in response');
                   }

                   // Store PDF report (correct field name from API)
                   if (execRes.report_base64_pdf) {
                       this.executionPdfReport = execRes.report_base64_pdf;
                   }

                   // Log execution logs if available
                   if (execRes.execution_logs && execRes.execution_logs.length > 0) {
                       console.log('[EXECUTION LOGS]', execRes.execution_logs);
                   }

                   this.successMessage = `✅ Exécution terminée. ${execRes.summary?.passed || 0} scénarios passés, ${execRes.summary?.failed || 0} échoués.`;
                   setTimeout(() => this.successMessage = '', 5000);
                },
                error: (execErr) => {
                   this.pipelineCurrentStep = 5;
                   this.pipelineProgress = 100;
                   this.pipelineStatus = 'error';
                   this.errorMessage = `Erreur lors de l'exécution Selenium: ${execErr.message}`;
                }
             });
          } else {
             setTimeout(() => this.successMessage = '', 5000);
          }
        },
        error: (error) => {
          // Erreur
          this.generatingId = null;
          if (project) {
            project.statut = 'ERREUR';
          }
          if (this.showWebTestingDashboard) {
             if (this.pipelineInterval) clearInterval(this.pipelineInterval);
             this.pipelineStatus = 'error';
             // Ne pas avancer la progress bar à 100 ! On la laisse s'arrêter à la phase où est survenue l'erreur.
             this.failedMetrics = '1';
             this.passedMetrics = '0';
          }
          this.errorMessage = '❌ Erreur lors de la génération : ' + error.message;
          setTimeout(() => this.errorMessage = '', 5000);
        }
      });
  }

  /**
   * Ouvre la modale pour voir les scénarios d'un projet TERMINE
   */
  viewScenarios(project: ProjectResponse): void {
    this.openUserStoryModal(project);
    this.loadScenarios(project.id);
  }

  /**
   * Ouvre la modale orientée vers le push Jira
   */
  onPushToJiraDirect(project: ProjectResponse): void {
    this.openUserStoryModal(project);
    this.loadScenarios(project.id);
  }

  /**
   * Récupère les scénarios persistés dans la db
   */
  private loadScenarios(projectId: string): void {
    this.isLoading = true; // small loading feedback if needed
    this.projectService.getProjectScenarios(projectId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (scenarios) => {
          this.generatedScenarios = scenarios;
          this.isLoading = false;
        },
        error: (err) => {
           console.error("Erreur gherkin fetch:", err);
           this.isLoading = false;
        }
      });
  }

  /**
   * Retourne le nom de fichier depuis un chemin (gère '/' et '\\')
   */
  getFilename(path?: string | null): string {
    if (!path) return '';
    const parts = path.split(/[/\\]/);
    let filename = parts.length ? parts[parts.length - 1] : path;
    // Retirer le préfixe UUID ajouté par le backend (format: uuid_nomfichier.ext)
    const uuidPrefixMatch = filename.match(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}_/i);
    if (uuidPrefixMatch) {
      filename = filename.substring(uuidPrefixMatch[0].length);
    }
    return filename;
  }

  /**
   * Retourne la classe CSS pour le badge de statut
   */
  getStatutClass(statut: string): string {
    const classes: { [key: string]: string } = {
      'EN_ATTENTE': 'badge-warning',   // jaune
      'EN_COURS': 'badge-info',        // bleu
      'TERMINE': 'badge-success',      // vert
      'ERREUR': 'badge-danger'         // rouge
    };
    return classes[statut] || 'badge-secondary';
  }

  /**
   * Alias pour editProject (pour le template HTML)
   */
  onEdit(project: ProjectResponse): void {
    this.editProject(project.id);
  }

  /**
   * Alias pour deleteProject (pour le template HTML)
   */
  onDelete(projectId: string): void {
    this.deleteProject(projectId);
  }

  /**
   * Ouvre la modale des détails d'une User Story
   */
  openUserStoryModal(project: ProjectResponse): void {
    this.selectedUserStoryProject = project;
    // Empêcher le défilement de l'arrière-plan
    document.body.style.overflow = 'hidden';
  }

  /**
   * Ferme la modale des détails de la User Story
   */
  closeUserStoryModal(): void {
    this.selectedUserStoryProject = null;
    this.generatedScenarios = null;
    this.generatingId = null;
    // Restaurer le défilement
    document.body.style.overflow = '';
  }

  /**
   * Ouvre la modale des détails généraux du projet
   */
  openProjectDetailsModal(project: ProjectResponse): void {
    this.selectedProjectForDetails = project;
    document.body.style.overflow = 'hidden';
  }

  /**
   * Ferme la modale des détails du projet
   */
  closeProjectDetailsModal(): void {
    this.selectedProjectForDetails = null;
    document.body.style.overflow = '';
  }

  // --- Web Testing Dashboard Methods ---
  openWebTestingDashboard(project: ProjectResponse): void {
    this.webTestingProject = project;
    this.showWebTestingDashboard = true;
    this.executionPdfReport = undefined;
    this.elementsDetected = '-';
    this.scenariosGenerated = '-';
    this.passedMetrics = '-';
    this.failedMetrics = '-';
    document.body.style.overflow = 'hidden';

    if (project.statut === 'TERMINE') {
      const cachedAtOpen = this.loadCachedExecutionMetrics(project.id);
      if (cachedAtOpen !== null && cachedAtOpen.elementsCount !== null) {
        this.elementsDetected = cachedAtOpen.elementsCount;
      }

      this.pipelineStatus = 'completed';
      this.pipelineCurrentStep = 5; // Report
      this.pipelineProgress = 100;

      // Fetch real execution metrics from backend
      this.projectService.getExecutionMetrics(project.id).subscribe({
        next: (metrics: any) => {
          // Load REAL metrics from database
          this.passedMetrics = (metrics?.passed ?? 0).toString();
          this.failedMetrics = (metrics?.failed ?? 0).toString();
          this.scenariosGenerated = (metrics?.total ?? 0).toString();
          if (metrics?.report_base64_pdf) {
            this.executionPdfReport = metrics.report_base64_pdf;
          }

          // If backend has no persisted metrics yet, restore last known local metrics.
          if ((metrics?.total ?? 0) === 0 && (metrics?.passed ?? 0) === 0 && (metrics?.failed ?? 0) === 0) {
            const cached = this.loadCachedExecutionMetrics(project.id);
            if (cached) {
              this.passedMetrics = cached.passed.toString();
              this.failedMetrics = cached.failed.toString();
              this.scenariosGenerated = cached.total.toString();
              if (cached.elementsCount !== null) {
                this.elementsDetected = cached.elementsCount;
              }
            }
          }
        },
        error: (err) => {
          console.warn('Could not load execution metrics, falling back to scenario count', err);
          const cached = this.loadCachedExecutionMetrics(project.id);
          if (cached) {
            this.passedMetrics = cached.passed.toString();
            this.failedMetrics = cached.failed.toString();
            this.scenariosGenerated = cached.total.toString();
            if (cached.elementsCount !== null) {
              this.elementsDetected = cached.elementsCount;
            }
          } else {
            // Fallback values when no backend data and no local cache exist.
            this.passedMetrics = '0';
            this.failedMetrics = '0';
            this.scenariosGenerated = '0';
          }
        }
      });

      // Fetch scénarios ML for reference
      this.projectService.getProjectScenarios(project.id).subscribe({
        next: (scripts) => {
          const filteredScripts = (scripts || []).filter((scenario: any) =>
            scenario?.type !== 'Selenium Script' && scenario?.nomSenario !== 'Script Selenium (CodeT5)'
          );
          // Charger les scénarios dans generatedScenarios pour l'affichage
          this.generatedScenarios = filteredScripts;

          if (this.scenariosGenerated === '0' && this.generatedScenarios.length > 0) {
            this.scenariosGenerated = this.generatedScenarios.length.toString();
          }

          const scenarioWithElements = filteredScripts.find((scenario: any) =>
            scenario?.elementsCount !== undefined && scenario?.elementsCount !== null
          );
          if (scenarioWithElements) {
            this.elementsDetected = scenarioWithElements.elementsCount;
            this.cacheElementsDetected(project.id, scenarioWithElements.elementsCount);
          }
        },
        error: (err) => console.error('Erreur chargement scénarios', err)
      });
    } else {
      this.pipelineStatus = 'generating';
      this.pipelineCurrentStep = 1; // Crawl
      this.pipelineProgress = 0;
      this.simulatePipeline();
    }
  }

  closeWebTestingDashboard(): void {
    this.showWebTestingDashboard = false;
    this.webTestingProject = null;
    this.generatedScenarios = null; // Réinitialiser les scénarios affichés dans le dashboard
    this.activeFileSpecTab = 'analyser'; // Réinitialiser à l'onglet "Analyser"
    if (this.pipelineInterval) clearInterval(this.pipelineInterval);
    document.body.style.overflow = '';
  }

  private getExecutionMetricsStorageKey(projectId: string): string {
    return `${this.executionMetricsStoragePrefix}${projectId}`;
  }

  private cacheExecutionMetrics(projectId: string, summary: any): void {
    try {
      const existingRaw = localStorage.getItem(this.getExecutionMetricsStorageKey(projectId));
      const existing = existingRaw ? JSON.parse(existingRaw) : {};
      const payload = {
        total: Number(summary?.total ?? 0),
        passed: Number(summary?.passed ?? 0),
        failed: Number(summary?.failed ?? 0),
        elementsCount: existing?.elementsCount !== undefined ? Number(existing.elementsCount) : null,
        updatedAt: new Date().toISOString()
      };
      localStorage.setItem(this.getExecutionMetricsStorageKey(projectId), JSON.stringify(payload));
    } catch (e) {
      console.warn('Could not cache execution metrics in localStorage', e);
    }
  }

  private cacheElementsDetected(projectId: string, elementsCount: any): void {
    try {
      const existingRaw = localStorage.getItem(this.getExecutionMetricsStorageKey(projectId));
      const existing = existingRaw ? JSON.parse(existingRaw) : {};
      const payload = {
        ...existing,
        elementsCount: Number(elementsCount ?? 0),
        updatedAt: new Date().toISOString()
      };
      localStorage.setItem(this.getExecutionMetricsStorageKey(projectId), JSON.stringify(payload));
    } catch (e) {
      console.warn('Could not cache elements detected in localStorage', e);
    }
  }

  private loadCachedExecutionMetrics(projectId: string): { total: number; passed: number; failed: number; elementsCount: number | null } | null {
    try {
      const raw = localStorage.getItem(this.getExecutionMetricsStorageKey(projectId));
      if (!raw) return null;

      const parsed = JSON.parse(raw);
      return {
        total: Number(parsed?.total ?? 0),
        passed: Number(parsed?.passed ?? 0),
        failed: Number(parsed?.failed ?? 0),
        elementsCount: parsed?.elementsCount !== undefined && parsed?.elementsCount !== null
          ? Number(parsed.elementsCount)
          : null
      };
    } catch (e) {
      console.warn('Could not parse cached execution metrics from localStorage', e);
      return null;
    }
  }

  simulatePipeline(): void {
    let tick = 0;
    this.pipelineInterval = setInterval(() => {
      tick++;
      if (tick <= 20) {
        this.pipelineCurrentStep = 1; // Crawling
        this.pipelineProgress = 10 + (tick * 0.5);
      } else if (tick <= 50) {
        this.pipelineCurrentStep = 2; // Analyzing
        this.pipelineProgress = 20 + ((tick-20) * 1);
      } else if (tick <= 80) {
        this.pipelineCurrentStep = 3; // Generating
        this.pipelineProgress = 50 + ((tick-50) * 1);
      } else if (tick <= 110) {
        this.pipelineCurrentStep = 4; // Executing
        this.pipelineProgress = 80 + ((tick-80) * 0.5);
      } else {
        // En attendant la réponse de l'API IA
        this.pipelineProgress = 95; 
      }
    }, 500); 
  }

  // --- Terminal UI methods ---
  openTerminalModal(project: ProjectResponse): void {
    this.selectedTerminalProject = project;
    this.terminalSteps = [];
    this.terminalProgress = 0;
    this.terminalTab = 'terminal';
    this.testResults = null;
    
    this.terminalScripts = [];
    if (project.statut === 'TERMINE') {
      this.projectService.getProjectScenarios(project.id).subscribe({
        next: (scenarios) => {
          this.terminalScripts = (scenarios || []).filter((scenario: any) =>
            scenario?.type !== 'Selenium Script' && scenario?.nomSenario !== 'Script Selenium (CodeT5)'
          );
        },
        error: (err) => console.error('Erreur chargement scripts', err)
      });
    }

    document.body.style.overflow = 'hidden';
    this.startTerminalSimulation();
  }

  closeTerminalModal(): void {
    this.selectedTerminalProject = null;
    if (this.terminalInterval) {
      clearTimeout(this.terminalInterval);
    }
    document.body.style.overflow = '';
  }

  setTerminalTab(tab: 'terminal' | 'scripts' | 'results'): void {
    this.terminalTab = tab;
  }

  startTerminalSimulation(): void {
    let stepsText: string[] = [];
    
    if (this.selectedTerminalProject?.executionLogs) {
      try {
        const logsArray = JSON.parse(this.selectedTerminalProject.executionLogs);
        if (Array.isArray(logsArray) && logsArray.length > 0) {
           stepsText = logsArray;
        }
      } catch (e) { console.error('Erreur parsing logs', e); }
    }
    
    if (stepsText.length === 0) {
      stepsText = [
        "Lancement du navigateur Chrome...",
        `Navigation vers ${this.selectedTerminalProject?.specificationContenu}...`,
        "Détection des éléments interactifs...",
        "Génération des scripts Pytest via IA...",
        "🎉 Analyse terminée avec succès !"
      ];
    }

    let currentStep = 0;

    const executeNextStep = () => {
      if (currentStep > 0 && currentStep <= stepsText.length) {
        this.terminalSteps[currentStep - 1].status = 'done';
      }

      if (currentStep < stepsText.length) {
        this.terminalSteps.push({ text: stepsText[currentStep], status: 'active' });
        this.terminalProgress = Math.round(((currentStep + 1) / stepsText.length) * 100);
        currentStep++;
        
        const delay = Math.floor(Math.random() * 500) + 300;
        this.terminalInterval = setTimeout(executeNextStep, delay);
      } else {
        this.terminalProgress = 100;
        if (this.terminalSteps.length > 0) {
          this.terminalSteps[this.terminalSteps.length - 1].status = 'done';
        }
      }
    };

    this.terminalInterval = setTimeout(executeNextStep, 300);
  }

  executePytest(): void {
    if (!this.selectedTerminalProject) return;
    
    this.terminalTab = 'results';
    this.isExecutingTests = true;
    this.testResults = null;
    
    this.projectService.runTests(this.selectedTerminalProject.id).subscribe({
      next: (res) => {
        this.isExecutingTests = false;
        this.testResults = res;
      },
      error: (err) => {
        this.isExecutingTests = false;
        this.testResults = { error: true, message: err.message };
      }
    });
  }

  copyToClipboard(code: string): void {
    navigator.clipboard.writeText(code).then(() => {
    });
  }

  downloadPdf(): void {
    if (!this.testResults || !this.testResults.pdf_base64) return;
    
    // Convert base64 to Blob
    const byteCharacters = atob(this.testResults.pdf_base64);
    const byteNumbers = new Array(byteCharacters.length);
    for (let i = 0; i < byteCharacters.length; i++) {
        byteNumbers[i] = byteCharacters.charCodeAt(i);
    }
    const byteArray = new Uint8Array(byteNumbers);
    const blob = new Blob([byteArray], {type: 'application/pdf'});
    
    // Create download link
    const link = document.createElement('a');
    link.href = window.URL.createObjectURL(blob);
    link.download = `Rapport_PFE_Selenium_${this.selectedTerminalProject?.nom || 'Projet'}.pdf`;
    link.click();
  }

  /**
   * Analyse le fichier HTML du projet CODE_FICHIER via BeautifulSoup
   */
  analyzeProjectFile(): void {
    if (!this.webTestingProject || this.isAnalyzingFile) return;

    this.isAnalyzingFile = true;
    this.fileAnalysisError = '';
    this.fileAnalysisFields = [];
    this.fileAnalysisBehaviors = [];
    this.fileAnalysisSummary = null;

    // 1. Récupérer le contenu du fichier depuis le backend
    this.projectService.getFileContent(this.webTestingProject.id)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (fileData: any) => {
          // 2. Décoder le base64 en texte HTML avec support de l'UTF-8 (pour les accents)
          let htmlContent = '';
          try {
            const binaryString = atob(fileData.content);
            const bytes = new Uint8Array(binaryString.length);
            for (let i = 0; i < binaryString.length; i++) {
              bytes[i] = binaryString.charCodeAt(i);
            }
            htmlContent = new TextDecoder('utf-8').decode(bytes);
          } catch (e) {
            console.error('Erreur de décodage base64:', e);
            this.fileAnalysisError = 'Erreur lors du décodage du fichier.';
            this.isAnalyzingFile = false;
            return;
          }

          // 3. Envoyer au Python API pour analyse BeautifulSoup
          this.projectService.analyzeHtml(htmlContent)
            .pipe(takeUntil(this.destroy$))
            .subscribe({
              next: (analysis: any) => {
                this.fileAnalysisFields = analysis.fields || [];
                this.fileAnalysisBehaviors = analysis.behaviors || [];
                this.fileAnalysisSummary = analysis.summary || null;
                this.elementsDetected = this.fileAnalysisFields.length;
                this.isAnalyzingFile = false;
              },
              error: (err) => {
                console.error('Erreur analyse HTML:', err);
                this.fileAnalysisError = 'Erreur lors de l\'analyse du fichier HTML.';
                this.isAnalyzingFile = false;
              }
            });
        },
        error: (err) => {
          console.error('Erreur récupération fichier:', err);
          this.fileAnalysisError = 'Impossible de récupérer le fichier du projet.';
          this.isAnalyzingFile = false;
        }
      });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  downloadPdfReport(): void {
    if (!this.executionPdfReport) {
      this.errorMessage = "Aucun rapport PDF disponible.";
      setTimeout(() => this.errorMessage = '', 5000);
      return;
    }
    const linkSource = `data:application/pdf;base64,${this.executionPdfReport}`;
    const downloadLink = document.createElement('a');
    const fileName = `Rapport_Execution_Selenium.pdf`;

    downloadLink.href = linkSource;
    downloadLink.download = fileName;
    downloadLink.click();
  }
}
