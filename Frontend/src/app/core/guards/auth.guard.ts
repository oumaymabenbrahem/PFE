import { Injectable } from '@angular/core';
import { CanActivate, ActivatedRouteSnapshot, RouterStateSnapshot, UrlTree, Router } from '@angular/router';
import { Observable } from 'rxjs';
import { AuthService } from '../services/auth.service';

@Injectable({
  providedIn: 'root'
})
export class AuthGuard implements CanActivate {
  constructor(
    private authService: AuthService,
    private router: Router
  ) { }

  /**
   * Vérifie si l'utilisateur peut accéder à la route
   * Redirige vers /login si l'utilisateur n'est pas authentifié
   * @param route - La route activée
   * @param state - L'état du routeur
   * @returns true si l'accès est autorisé, false sinon
   */
  canActivate(
    route: ActivatedRouteSnapshot,
    state: RouterStateSnapshot
  ): Observable<boolean | UrlTree> | Promise<boolean | UrlTree> | boolean | UrlTree {
    
    // Vérifier si l'utilisateur est connecté
    if (this.authService.isLoggedIn()) {
      return true;
    }

    // L'utilisateur n'est pas connecté
    console.warn('Accès refusé: utilisateur non authentifié');

    // Rediriger vers la page de connexion
    this.router.navigate(['/login'], {
      queryParams: { returnUrl: state.url }
    });

    return false;
  }
}
