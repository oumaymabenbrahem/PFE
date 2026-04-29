import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

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
