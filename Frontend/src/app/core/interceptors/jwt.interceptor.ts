import { Injectable } from '@angular/core';
import {
  HttpRequest,
  HttpHandler,
  HttpEvent,
  HttpInterceptor,
  HttpErrorResponse
} from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { AuthService } from '../services/auth.service';
import { Router } from '@angular/router';

@Injectable()
export class JwtInterceptor implements HttpInterceptor {

  // Routes publiques qui ne nécessitent pas le token JWT
   private publicRoutes: string[] = [
    '/api/auth/login',
    '/api/auth/register',
    '/assets/',
    'localhost:8000'
  ];

  constructor(
    private authService: AuthService,
    private router: Router
  ) { }

  /**
   * Intercepte toutes les requêtes HTTP
   * Ajoute le token JWT aux requêtes non-publiques
   * Gère les erreurs d'authentification (401, 403)
   */
  intercept(request: HttpRequest<any>, next: HttpHandler): Observable<HttpEvent<any>> {
    // Vérifier si c'est une route publique
    if (this.isPublicRoute(request.url)) {
      return next.handle(request);
    }

    // Récupérer le token
    const token = this.authService.getToken();

    // Si un token existe, l'ajouter au header
    if (token) {
      request = this.addTokenToRequest(request, token);
    }

    // Passer la requête au prochain handler et gérer les erreurs
    return next.handle(request)
      .pipe(
        catchError((error: HttpErrorResponse) => {
          if (error.status === 401 || error.status === 403) {
            // Authentification échouée ou token expiré
            console.warn('Token expiré ou non valide');
            this.authService.logout();
          }
          return throwError(() => error);
        })
      );
  }

  /**
   * Ajoute le token JWT au header Authorization
   * @param request - La requête HTTP
   * @param token - Le token JWT
   * @returns La requête modifiée
   */
  private addTokenToRequest(request: HttpRequest<any>, token: string): HttpRequest<any> {
    return request.clone({
      setHeaders: {
        Authorization: `Bearer ${token}`
      }
    });
  }

  /**
   * Vérifie si l'URL est une route publique
   * @param url - L'URL à vérifier
   * @returns true si la route est publique, false sinon
   */
  private isPublicRoute(url: string): boolean {
    return this.publicRoutes.some(route => url.includes(route));
  }
}
