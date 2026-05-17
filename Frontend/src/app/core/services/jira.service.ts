import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface JiraProject {
  id: string;
  key: string;
  name: string;
}

/**
 * Service pour gérer l'intégration Jira et Xray
 */
@Injectable({
  providedIn: 'root'
})
export class JiraService {
  private apiUrl = 'http://localhost:8081/api/jira';

  constructor(private http: HttpClient) {}

  /**
   * Vérifie si l'utilisateur est actuellement connecté à Jira
   */
  getStatus(): Observable<{ connected: boolean }> {
    return this.http.get<{ connected: boolean }>(`${this.apiUrl}/status`);
  }

  /**
   * Récupère l'URL d'autorisation OAuth Atlassian
   */
  getLoginUrl(): Observable<{ url: string }> {
    return this.http.get<{ url: string }>(`${this.apiUrl}/login`);
  }

  /**
   * Récupère les projets Jira visibles par l'utilisateur connecté
   */
  getProjects(): Observable<JiraProject[]> {
    return this.http.get<JiraProject[]>(`${this.apiUrl}/projects`);
  }

  /**
   * Vérifie si l'utilisateur a configuré ses clés Xray Cloud
   */
  getXrayConfigStatus(): Observable<{ configured: boolean; baseUrl: string }> {
    return this.http.get<{ configured: boolean; baseUrl: string }>(`${this.apiUrl}/xray-config/status`);
  }

  /**
   * Enregistre les clés Xray Cloud de l'utilisateur connecté
   */
  saveXrayConfig(clientId: string, clientSecret: string, baseUrl: string): Observable<any> {
    return this.http.post<any>(`${this.apiUrl}/xray-config`, { clientId, clientSecret, baseUrl });
  }

  /**
   * Pousse les scénarios générés vers Xray
   */
  pushTests(projectKey: string, userStoryId: string, scenarios: any[]): Observable<any> {
    const payload = {
      projectKey,
      userStoryId,
      scenarios
    };
    return this.http.post<any>(`${this.apiUrl}/push-tests`, payload);
  }
}
