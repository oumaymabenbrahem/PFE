import { Component, OnInit } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { AuthService } from '../../../core/services/auth.service';

@Component({
  selector: 'app-reset-password',
  templateUrl: './reset-password.component.html',
  styleUrls: ['./reset-password.component.scss']
})
export class ResetPasswordComponent implements OnInit {
  emailMask = '';
  password = '';
  confirmPassword = '';
  showPassword = false;
  showConfirmPassword = false;
  errorMessage = '';
  successMessage = '';
  isLoading = false;

  private resetToken = '';

  // Password strength
  strengthLevel = 0; // 0-4
  strengthLabel = '';
  strengthColor = '';
  strengthCriteria = {
    minLength: false,
    hasUppercase: false,
    hasLowercase: false,
    hasNumber: false,
    hasSpecial: false
  };

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private authService: AuthService
  ) {}

  ngOnInit(): void {
    const email = this.route.snapshot.queryParamMap.get('email') || '';
    this.resetToken = this.route.snapshot.queryParamMap.get('token') || '';
    this.emailMask = email ? this.maskEmail(email) : '';

    // Rediriger si pas de token
    if (!this.resetToken) {
      this.router.navigate(['/forgot-password']);
    }
  }

  togglePasswordVisibility(field: 'password' | 'confirm'): void {
    if (field === 'password') {
      this.showPassword = !this.showPassword;
      return;
    }

    this.showConfirmPassword = !this.showConfirmPassword;
  }

  onPasswordInput(): void {
    this.evaluatePasswordStrength();
  }

  onSubmit(): void {
    this.errorMessage = '';
    this.successMessage = '';

    if (this.password.length < 8) {
      this.errorMessage = 'Le mot de passe doit contenir au moins 8 caractères.';
      return;
    }

    if (this.password !== this.confirmPassword) {
      this.errorMessage = 'Les mots de passe ne correspondent pas.';
      return;
    }

    this.isLoading = true;

    this.authService.resetPassword(this.resetToken, this.password).subscribe({
      next: () => {
        this.isLoading = false;
        this.successMessage = 'Mot de passe réinitialisé avec succès ! Redirection...';
        setTimeout(() => this.router.navigate(['/login']), 2000);
      },
      error: (err: Error) => {
        this.isLoading = false;
        this.errorMessage = err.message || 'Erreur lors de la réinitialisation.';
      }
    });
  }

  private evaluatePasswordStrength(): void {
    const pwd = this.password;

    this.strengthCriteria = {
      minLength: pwd.length >= 8,
      hasUppercase: /[A-Z]/.test(pwd),
      hasLowercase: /[a-z]/.test(pwd),
      hasNumber: /[0-9]/.test(pwd),
      hasSpecial: /[^A-Za-z0-9]/.test(pwd)
    };

    const criteriaMet = Object.values(this.strengthCriteria).filter(v => v).length;

    if (pwd.length === 0) {
      this.strengthLevel = 0;
      this.strengthLabel = '';
      this.strengthColor = '';
    } else if (criteriaMet <= 1) {
      this.strengthLevel = 1;
      this.strengthLabel = 'Faible';
      this.strengthColor = '#dc3545';
    } else if (criteriaMet <= 2) {
      this.strengthLevel = 2;
      this.strengthLabel = 'Moyen';
      this.strengthColor = '#f59e0b';
    } else if (criteriaMet <= 3) {
      this.strengthLevel = 3;
      this.strengthLabel = 'Fort';
      this.strengthColor = '#22c55e';
    } else {
      this.strengthLevel = 4;
      this.strengthLabel = 'Très fort';
      this.strengthColor = '#1f4e8a';
    }
  }

  private maskEmail(email: string): string {
    const [localPart, domainPart] = email.split('@');
    if (!localPart || !domainPart) {
      return 'v***@exemple.com';
    }

    return `${localPart.charAt(0)}***@${domainPart}`;
  }
}
