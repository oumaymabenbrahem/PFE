import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { BehaviorSubject, Observable, throwError } from 'rxjs';
import { catchError, map, tap } from 'rxjs/operators';
import { User, LoginDto, RegisterDto, AuthResponse, JwtPayload } from '../../shared/models/user.model';
import { Router } from '@angular/router';

@Injectable({
  providedIn: 'root'
})
export class AuthService {
  private readonly API_URL = 'http://localhost:8081/api/auth'; 
  private readonly TOKEN_KEY = 'auth_token';
  private readonly USER_KEY = 'auth_user';

  
    //BehaviorSubject contenant l'utilisateur actuellement connecté
    //null si l'utilisateur n'est pas authentifié
   
  private currentUserSubject = new BehaviorSubject<User | null>(null);
  public currentUser$ = this.currentUserSubject.asObservable();

  
   // BehaviorSubject tracking l'état de chargement
   
  private isLoadingSubject = new BehaviorSubject<boolean>(false);
  public isLoading$ = this.isLoadingSubject.asObservable();

  constructor(
    private httpClient: HttpClient,
    private router: Router
  ) {
    this.initializeFromLocalStorage();
  }

  
   // Initialise l'authentification à partir du localStorage au démarrage
   // Décide automatiquement le token JWT s'il existe
 
  private initializeFromLocalStorage(): void {
    const token = this.getToken();
    const savedUser = localStorage.getItem(this.USER_KEY);

    if (token && savedUser) {
      try {
        const user: User = JSON.parse(savedUser);
        // Vérifier si le token est valide (pas expiré)
        if (this.isTokenValid(token)) {
          this.currentUserSubject.next(user);
        } else {
          // Token expiré, nettoyer
          this.logout();
        }
      } catch (error) {
        console.error('Erreur lors du parsing de l\'utilisateur sauvegardé', error);
        this.logout();
      }
    }
  }

  /**
   * Authentifie un utilisateur avec email et mot de passe
   * @param credentials - Email et mot de passe
   * @returns Observable contenant la réponse d'authentification
   */
  public login(credentials: LoginDto): Observable<AuthResponse> {
    this.isLoadingSubject.next(true);

    return this.httpClient.post<AuthResponse>(`${this.API_URL}/login`, credentials)
      .pipe(
        tap(response => this.handleAuthResponse(response)),
        map(response => {
          this.isLoadingSubject.next(false);
          return response;
        }),
        catchError(error => {
          this.isLoadingSubject.next(false);
          console.error('Erreur de connexion:', error);
          return throwError(() => new Error(error.error?.message || 'Erreur de connexion'));
        })
      );
  }

  /**
   * Enregistre un nouvel utilisateur
   * @param data - Données d'enregistrement (email, nom, password)
   * @returns Observable contenant la réponse d'authentification
   */
  public register(data: RegisterDto): Observable<AuthResponse> {
    this.isLoadingSubject.next(true);

    return this.httpClient.post<AuthResponse>(`${this.API_URL}/register`, data)
      .pipe(
        tap(response => this.handleAuthResponse(response)),
        map(response => {
          this.isLoadingSubject.next(false);
          return response;
        }),
        catchError(error => {
          this.isLoadingSubject.next(false);
          console.error('Erreur d\'enregistrement:', error);
          return throwError(() => new Error(error.error?.message || 'Erreur d\'enregistrement'));
        })
      );
  }

  /**
   * Traite la réponse d'authentification
   * Sauvegarde le token et l'utilisateur dans localStorage et met à jour l'état
   * @param response - Réponse du serveur
   */
  private handleAuthResponse(response: AuthResponse): void {
    // Sauvegarder le token
    localStorage.setItem(this.TOKEN_KEY, response.token);
    // Sauvegarder l'utilisateur
    localStorage.setItem(this.USER_KEY, JSON.stringify(response.user));
    // Mettre à jour le BehaviorSubject
    this.currentUserSubject.next(response.user);
  }

  /**
   * Déconnecte l'utilisateur
   * Nettoie la session et les données sauvegardées
   */
  public logout(): void {
    // Nettoyer localStorage
    localStorage.removeItem(this.TOKEN_KEY);
    localStorage.removeItem(this.USER_KEY);

    // Réinitialiser le BehaviorSubject
    this.currentUserSubject.next(null);

    // Rediriger vers la page de connexion
    this.router.navigate(['/login']);
  }

  /**
   * Vérifie si l'utilisateur est connecté
   * @returns true si l'utilisateur est authentifié, false sinon
   */
  public isLoggedIn(): boolean {
    const token = this.getToken();
    return token !== null && this.isTokenValid(token);
  }

  /**
   * Récupère le token JWT du localStorage
   * @returns Le token ou null s'il n'existe pas
   */
  public getToken(): string | null {
    return localStorage.getItem(this.TOKEN_KEY);
  }

  /**
   * Récupère l'utilisateur connecté actuellement
   * @returns L'utilisateur ou null s'il n'est pas connecté
   */
  public getCurrentUser(): User | null {
    return this.currentUserSubject.value;
  }

  /**
   * Décide le payload JWT
   * @param token - Le token JWT
   * @returns Le payload décodé
   */
  public decodeToken(token: string): JwtPayload | null {
    try {
      const parts = token.split('.');
      if (parts.length !== 3) {
        console.error('Token invalide: format incorrect');
        return null;
      }

      // Décoder la deuxième partie (payload)
      const payload = JSON.parse(atob(parts[1]));
      return payload;
    } catch (error) {
      console.error('Erreur lors du décodage du token:', error);
      return null;
    }
  }

  /**
   * Vérifie si le token est valide et non expiré
   * @param token - Le token à vérifier
   * @returns true si le token est valide, false sinon
   */
  private isTokenValid(token: string): boolean {
    const payload = this.decodeToken(token);
    if (!payload) {
      return false;
    }

    // Vérifier si le token a expiré
    const expirationTime = payload.exp * 1000; //  en millisecondes
    const currentTime = Date.now();

    return currentTime < expirationTime;
  }

  /**
   * Récupère le temps d'expiration du token en secondes
   * @returns Temps d'expiration du token ou null si pas de token valide
   */
  public getTokenExpirationTime(): number | null {
    const token = this.getToken();
    if (!token) {
      return null;
    }

    const payload = this.decodeToken(token);
    return payload ? payload.exp : null;
  }

  // ==================== MOT DE PASSE OUBLIÉ ====================

  /**
   * Étape 1 : Envoie un code OTP à l'adresse email
   */
  public forgotPassword(email: string): Observable<any> {
    return this.httpClient.post(`${this.API_URL}/forgot-password`, { email }).pipe(
      catchError(error => {
        console.error('Erreur forgot-password:', error);
        return throwError(() => new Error(error.error?.message || 'Erreur lors de l\'envoi du code'));
      })
    );
  }

  /**
   * Étape 2 : Vérifie le code OTP et retourne un resetToken
   */
  public verifyOtp(email: string, otpCode: string): Observable<any> {
    return this.httpClient.post(`${this.API_URL}/verify-otp`, { email, otpCode }).pipe(
      catchError(error => {
        console.error('Erreur verify-otp:', error);
        return throwError(() => new Error(error.error?.message || 'Code invalide ou expiré'));
      })
    );
  }

  /**
   * Étape 3 : Réinitialise le mot de passe avec le resetToken
   */
  public resetPassword(resetToken: string, newPassword: string): Observable<any> {
    return this.httpClient.post(`${this.API_URL}/reset-password`, { resetToken, newPassword }).pipe(
      catchError(error => {
        console.error('Erreur reset-password:', error);
        return throwError(() => new Error(error.error?.message || 'Erreur lors de la réinitialisation'));
      })
    );
  }
}
