import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError, timeout } from 'rxjs/operators';
import { ProjectRequest, ProjectResponse } from '../../shared/models/project.model';

@Injectable({
  providedIn: 'root'
})
export class ProjectService {
  private readonly API_URL = 'http://localhost:8081/api/projects';
  private readonly GENERATE_TESTS_TIMEOUT_MS = 900000; // 15 minutes: crawl + locator validation + IA can be slow.

  constructor(private httpClient: HttpClient) {}

  /**
   * Créer un nouveau projet avec fichier uploadé
   * @param formData - FormData contenant:
   *   - "request": Blob JSON avec les données du projet
   *   - "fichier": File optionnel
   */
  createProject(formData: FormData): Observable<ProjectResponse> {
    return this.httpClient.post<ProjectResponse>(this.API_URL, formData)
      .pipe(
        catchError(error => {
          console.error('Erreur lors de la création du projet:', error);
          let errorMessage = 'Erreur lors de la création du projet';
          
          if (error.error && error.error.message) {
            errorMessage = error.error.message;
          } else if (error.status === 400) {
            errorMessage = 'Erreur de validation. Veuillez vérifier vos données.';
          } else if (error.status === 403) {
            errorMessage = 'Accès refusé. Vous n\'êtes pas autorisé à créer ce projet.';
          } else if (error.status === 413) {
            errorMessage = 'Le fichier est trop volumineux (max 50MB).';
          } else if (error.status === 500) {
            errorMessage = 'Erreur serveur. Veuillez réessayer plus tard.';
          }
          
          return throwError(() => new Error(errorMessage));
        })
      );
  }

  // Récupèrer tous les projets de l'utilisateur connecté
  getMyProjects(): Observable<ProjectResponse[]> {
    return this.httpClient.get<ProjectResponse[]>(this.API_URL)
      .pipe(
        catchError(error => {
          console.error('Erreur lors de la récupération des projets:', error);
          return throwError(() => new Error('Erreur lors de la récupération des projets'));
        })
      );
  }

   // Récupèrer les détails d'un projet
  getproject(id: string): Observable<ProjectResponse> {
    return this.httpClient.get<ProjectResponse>(`${this.API_URL}/${id}`)
      .pipe(
        catchError(error => {
          console.error('Erreur lors de la récupération du projet:', error);
          return throwError(() => new Error('Projet non trouvé'));
        })
      );
  }

  // Met à jour un projet
  updateProject(id: string, projectRequest: ProjectRequest): Observable<ProjectResponse> {
    return this.httpClient.put<ProjectResponse>(`${this.API_URL}/${id}`, projectRequest)
      .pipe(
        catchError(error => {
          console.error('Erreur lors de la mise à jour du projet:', error);
          return throwError(() => new Error('Erreur lors de la mise à jour'));
        })
      );
  }

  // Supprimer un projet
  deleteProject(id: string): Observable<any> {
    return this.httpClient.delete(`${this.API_URL}/${id}`)
      .pipe(
        catchError(error => {
          console.error('Erreur lors de la suppression du projet:', error);
          return throwError(() => new Error('Erreur lors de la suppression'));
        })
      );
  }

  /**
   * Générer les scripts Selenium pour un projet
   * POST /api/projects/{id}/generate-tests
   */
  generateTests(projectId: string): Observable<any> {
    return this.httpClient.post<any>(
      `${this.API_URL}/${projectId}/generate-tests`,
      {}
    ).pipe(
      timeout(this.GENERATE_TESTS_TIMEOUT_MS),
      catchError(error => {
        console.error('Erreur lors de la génération des tests:', error);
        let errorMessage = 'Erreur lors de la génération des tests';
        
        if (error.name === 'TimeoutError') {
          errorMessage = 'La génération prend trop de temps. Le crawl, la validation des locators et l’IA peuvent dépasser plusieurs minutes sur les sites lourds.';
        } else if (error.error && error.error.message) {
          errorMessage = error.error.message;
        } else if (error.status === 400) {
          errorMessage = 'Requête invalide. Veuillez vérifier le projet.';
        } else if (error.status === 404) {
          errorMessage = 'Projet non trouvé.';
        } else if (error.status === 500) {
          errorMessage = 'Erreur serveur. Veuillez réessayer plus tard.';
        }
        
        return throwError(() => new Error(errorMessage));
      })
    );
  }

  /**
   * Récupérer les scripts générés pour un projet
   * GET /api/projects/{id}/scripts
   */
  getScripts(projectId: string): Observable<any> {
    return this.httpClient.get<any>(`${this.API_URL}/${projectId}/scripts`)
      .pipe(
        catchError(error => {
          console.error('Erreur lors de la récupération des scripts:', error);
          return throwError(() => new Error('Erreur lors de la récupération des scripts'));
        })
      );
  }

  // Obtenir les scénarios persistés d'un projet
  getProjectScenarios(id: string): Observable<any[]> {
    return this.httpClient.get<any[]>(`${this.API_URL}/${id}/scenarios`);
  }

  // Mettre à jour les scénarios d'un projet
  updateScenarios(projectId: string, scenarios: any[]): Observable<any> {
    return this.httpClient.put<any>(`${this.API_URL}/${projectId}/scenarios`, scenarios);
  }

  // Lancer l'exécution des tests
  runTests(id: string, selectedScenarioIds: string[] = []): Observable<any> {
    return this.httpClient.post<any>(`${this.API_URL}/${id}/run-tests`, { selectedScenarioIds }).pipe(
      catchError(error => {
        console.error('Erreur lors de l\'exécution des tests:', error);
        return throwError(() => new Error('Erreur lors de l\'exécution des tests'));
      })
    );
  }

  // Get execution metrics for a project
  getExecutionMetrics(projectId: string): Observable<any> {
    return this.httpClient.get<any>(`${this.API_URL}/${projectId}/execution-metrics`).pipe(
      catchError(error => {
        console.error('Erreur lors de la récupération des métriques d\'exécution:', error);
        return throwError(() => new Error('Impossible de récupérer les métriques'));
      })
    );
  }

  // Get file content (base64) for CODE_FICHIER projects
  getFileContent(projectId: string): Observable<any> {
    return this.httpClient.get<any>(`${this.API_URL}/${projectId}/file-content`).pipe(
      catchError(error => {
        console.error('Erreur lors de la récupération du fichier:', error);
        return throwError(() => new Error('Impossible de récupérer le fichier'));
      })
    );
  }

  // Analyze HTML content via Java Backend (which proxies to Python and persists results)
  analyzeHtml(projectId: string, htmlContent: string): Observable<any> {
    return this.httpClient.post<any>(`${this.API_URL}/${projectId}/analyze-html`, {
      html_content: htmlContent
    }).pipe(
      timeout(60000),
      catchError(error => {
        console.error('Erreur lors de l\'analyse HTML:', error);
        return throwError(() => new Error('Erreur d\'analyse HTML via Backend'));
      })
    );
  }

  // Generate Selenium script via Code T5 proxy (persists result in Backend)
  generateFileSelenium(projectId: string, fields: any[], tests: any[], htmlContent: string, focusObjective: string = ''): Observable<any> {
    return this.httpClient.post<any>(`${this.API_URL}/${projectId}/generate-file-selenium`, {
      fields: fields,
      tests: tests,
      html_content: htmlContent,
      focus_objective: focusObjective
    }).pipe(
      timeout(120000),
      catchError(error => {
        console.error('Erreur lors de la génération du script Selenium:', error);
        return throwError(() => new Error('Erreur de génération via Backend'));
      })
    );
  }

  // Execute Selenium script on HTML file via Java Proxy (persists report in Backend)
  runFileSelenium(projectId: string, scriptCode: string, htmlContentBase64: string, tests: any[] = [], fields: any[] = [], focusObjective: string = ''): Observable<any> {
    return this.httpClient.post<any>(`${this.API_URL}/${projectId}/run-file-selenium`, {
      script_code: scriptCode,
      html_content: htmlContentBase64,
      tests: tests,
      fields: fields,
      focus_objective: focusObjective
    }).pipe(
      timeout(3600000),
      catchError(error => {
        console.error('Erreur lors de l\'exécution Selenium:', error);
        const backendMessage = error?.error?.message
          || error?.error?.error
          || error?.message
          || 'Erreur d\'exécution via Backend';
        return throwError(() => new Error(backendMessage));
      })
    );
  }
}
