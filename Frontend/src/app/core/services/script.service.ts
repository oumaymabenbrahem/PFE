import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError, timeout } from 'rxjs/operators';

export interface GenerateTestScriptRequest {
  scenario_text: string;
  framework: string;
  language: string;
}

export interface GenerateTestScriptResponse {
  framework: string;
  language: string;
  file_name: string;
  generated_code: string;
}

@Injectable({
  providedIn: 'root'
})
export class ScriptService {
  private readonly PYTHON_API_URL = 'http://localhost:8000/api/generate-test-script';

  constructor(private httpClient: HttpClient) {}

  generateTestScript(request: GenerateTestScriptRequest): Observable<GenerateTestScriptResponse> {
    return this.httpClient.post<GenerateTestScriptResponse>(this.PYTHON_API_URL, request)
      .pipe(
        timeout(120000), // 2 minutes timeout for LLM generation
        catchError(error => {
          console.error('Erreur lors de la génération du script:', error);
          let errorMessage = 'Erreur lors de la génération du script';

          if (error.error && error.error.detail) {
            errorMessage = error.error.detail;
          } else if (error.status === 400) {
            errorMessage = 'Requête invalide. Veuillez vérifier vos données.';
          } else if (error.status === 502) {
            errorMessage = 'Erreur du modèle IA. Veuillez réessayer plus tard.';
          } else if (error.name === 'TimeoutError') {
            errorMessage = 'La génération a pris trop de temps. Veuillez réessayer.';
          }

          return throwError(() => new Error(errorMessage));
        })
      );
  }
}
