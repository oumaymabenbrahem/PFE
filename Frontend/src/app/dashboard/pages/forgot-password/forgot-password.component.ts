import { Component } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from '../../../core/services/auth.service';

@Component({
  selector: 'app-forgot-password',
  templateUrl: './forgot-password.component.html',
  styleUrls: ['./forgot-password.component.scss']
})
export class ForgotPasswordComponent {
  email = '';
  submitted = false;
  errorMessage = '';
  isLoading = false;

  constructor(
    private router: Router,
    private authService: AuthService
  ) {}

  onSubmit(): void {
    this.submitted = false;
    this.errorMessage = '';

    const normalizedEmail = this.email.trim();
    const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

    if (!normalizedEmail || !emailRegex.test(normalizedEmail)) {
      this.errorMessage = 'Veuillez saisir une adresse e-mail valide.';
      return;
    }

    this.isLoading = true;

    this.authService.forgotPassword(normalizedEmail).subscribe({
      next: () => {
        this.isLoading = false;
        this.submitted = true;
        // Rediriger vers la page de vérification OTP après 500ms
        setTimeout(() => {
          this.router.navigate(['/forgot-password/verification'], {
            queryParams: { email: normalizedEmail }
          });
        }, 500);
      },
      error: (err: Error) => {
        this.isLoading = false;
        this.errorMessage = err.message || 'Une erreur est survenue. Veuillez réessayer.';
      }
    });
  }
}
