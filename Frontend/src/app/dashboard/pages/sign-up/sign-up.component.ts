import { Component, OnInit, AfterViewInit } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from '../../../core/services/auth.service';
import { RegisterDto } from '../../../shared/models/user.model';

@Component({
  selector: 'app-sign-up',
  templateUrl: './sign-up.component.html',
  styleUrls: ['./sign-up.component.scss']
})
export class SignUpComponent implements OnInit, AfterViewInit {
  private form!: HTMLFormElement;
  private nameInput!: HTMLInputElement;
  private emailInput!: HTMLInputElement;
  private passwordInput!: HTMLInputElement;
  private confirmPasswordInput!: HTMLInputElement;
  private passwordToggle!: HTMLButtonElement;
  private confirmPasswordToggle!: HTMLButtonElement;
  private submitButton!: HTMLButtonElement;
  private successMessage!: HTMLElement;

  constructor(
    private authService: AuthService,
    private router: Router
  ) { }

  ngOnInit(): void {
  }

  ngAfterViewInit(): void {
    this.initializeForm();
    this.bindEvents();
    this.setupPasswordToggles();
    this.setupGentleEffects();
  }

  private initializeForm(): void {
    this.form = document.getElementById('signupForm') as HTMLFormElement;
    this.nameInput = document.getElementById('name') as HTMLInputElement;
    this.emailInput = document.getElementById('email') as HTMLInputElement;
    this.passwordInput = document.getElementById('password') as HTMLInputElement;
    this.confirmPasswordInput = document.getElementById('confirmPassword') as HTMLInputElement;
    this.passwordToggle = document.getElementById('passwordToggle') as HTMLButtonElement;
    this.confirmPasswordToggle = document.getElementById('confirmPasswordToggle') as HTMLButtonElement;
    this.submitButton = this.form.querySelector('.comfort-button') as HTMLButtonElement;
    this.successMessage = document.getElementById('successMessage') as HTMLElement;
  }

  private bindEvents(): void {
    this.form.addEventListener('submit', (e) => this.handleSubmit(e));
    this.nameInput.addEventListener('blur', () => this.validateName());
    this.emailInput.addEventListener('blur', () => this.validateEmail());
    this.passwordInput.addEventListener('blur', () => this.validatePassword());
    this.confirmPasswordInput.addEventListener('blur', () => this.validateConfirmPassword());
    
    [this.nameInput, this.emailInput, this.passwordInput, this.confirmPasswordInput].forEach(input => {
      input.addEventListener('input', () => this.clearError(input.id));
    });

    this.nameInput.setAttribute('placeholder', ' ');
    this.emailInput.setAttribute('placeholder', ' ');
    this.passwordInput.setAttribute('placeholder', ' ');
    this.confirmPasswordInput.setAttribute('placeholder', ' ');

    // Setup floating labels
    this.setupFloatingLabels();
  }

  private setupFloatingLabels(): void {
    [this.nameInput, this.emailInput, this.passwordInput, this.confirmPasswordInput].forEach(input => {
      const label = this.form.querySelector(`label[for="${input.id}"]`) as HTMLElement;
      if (!label) return;

      const updateLabelState = () => {
        if (input.value.trim() || document.activeElement === input) {
          label.classList.add('floating');
        } else {
          label.classList.remove('floating');
        }
      };

      input.addEventListener('focus', updateLabelState);
      input.addEventListener('blur', updateLabelState);
      input.addEventListener('input', updateLabelState);

      // Initial state
      updateLabelState();
    });
  }

  private setupPasswordToggles(): void {
    this.passwordToggle.addEventListener('click', () => {
      const type = this.passwordInput.type === 'password' ? 'text' : 'password';
      this.passwordInput.type = type;
      this.passwordToggle.classList.toggle('toggle-active', type === 'text');
    });

    this.confirmPasswordToggle.addEventListener('click', () => {
      const type = this.confirmPasswordInput.type === 'password' ? 'text' : 'password';
      this.confirmPasswordInput.type = type;
      this.confirmPasswordToggle.classList.toggle('toggle-active', type === 'text');
    });
  }

  private setupGentleEffects(): void {
    [this.nameInput, this.emailInput, this.passwordInput, this.confirmPasswordInput].forEach(input => {
      input.addEventListener('focus', (e) => {
        this.triggerSoftFocus((e.target as HTMLElement).closest('.field-container') as HTMLElement);
      });

      input.addEventListener('blur', (e) => {
        this.releaseSoftFocus((e.target as HTMLElement).closest('.field-container') as HTMLElement);
      });
    });

    this.addGentleClickEffects();
  }

  private triggerSoftFocus(container: HTMLElement): void {
    container.style.transition = 'all 0.3s ease';
    container.style.transform = 'translateY(-1px)';
  }

  private releaseSoftFocus(container: HTMLElement): void {
    container.style.transform = 'translateY(0)';
  }

  private addGentleClickEffects(): void {
    const interactiveElements = document.querySelectorAll('.comfort-button, .gentle-checkbox');

    interactiveElements.forEach(element => {
      element.addEventListener('mousedown', () => {
        (element as HTMLElement).style.transform = 'scale(0.98)';
      });

      element.addEventListener('mouseup', () => {
        (element as HTMLElement).style.transform = 'scale(1)';
      });

      element.addEventListener('mouseleave', () => {
        (element as HTMLElement).style.transform = 'scale(1)';
      });
    });
  }

  private validateName(): boolean {
    const name = this.nameInput.value.trim();

    if (!name) {
      this.showError('name', 'Veuillez saisir votre nom');
      return false;
    }

    if (name.length < 2) {
      this.showError('name', 'Le nom doit contenir au moins 2 caractères');
      return false;
    }

    this.clearError('name');
    return true;
  }

  private validateEmail(): boolean {
    const email = this.emailInput.value.trim();
    const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

    if (!email) {
      this.showError('email', 'Veuillez saisir votre adresse e-mail');
      return false;
    }

    if (!emailRegex.test(email)) {
      this.showError('email', 'Veuillez saisir une adresse e-mail valide');
      return false;
    }

    this.clearError('email');
    return true;
  }

  private validatePassword(): boolean {
    const password = this.passwordInput.value;

    if (!password) {
      this.showError('password', 'Veuillez saisir un mot de passe');
      return false;
    }

    if (password.length < 6) {
      this.showError('password', 'Le mot de passe doit contenir au moins 6 caractères');
      return false;
    }

    this.clearError('password');
    return true;
  }

  private validateConfirmPassword(): boolean {
    const confirmPassword = this.confirmPasswordInput.value;
    const password = this.passwordInput.value;

    if (!confirmPassword) {
      this.showError('confirmPassword', 'Veuillez confirmer votre mot de passe');
      return false;
    }

    if (confirmPassword !== password) {
      this.showError('confirmPassword', 'Les mots de passe ne correspondent pas');
      return false;
    }

    this.clearError('confirmPassword');
    return true;
  }

  private showError(field: string, message: string): void {
    const softField = document.getElementById(field)?.closest('.soft-field') as HTMLElement;
    const errorElement = document.getElementById(`${field}Error`) as HTMLElement;

    if (softField && errorElement) {
      softField.classList.add('error');
      errorElement.textContent = message;
      errorElement.classList.add('show');
    }
  }

  private clearError(field: string): void {
    const softField = document.getElementById(field)?.closest('.soft-field') as HTMLElement;
    const errorElement = document.getElementById(`${field}Error`) as HTMLElement;

    if (softField && errorElement) {
      softField.classList.remove('error');
      errorElement.classList.remove('show');
      setTimeout(() => {
        errorElement.textContent = '';
      }, 300);
    }
  }

  private async handleSubmit(e: Event): Promise<void> {
    e.preventDefault();

    const isNameValid = this.validateName();
    const isEmailValid = this.validateEmail();
    const isPasswordValid = this.validatePassword();
    const isConfirmPasswordValid = this.validateConfirmPassword();

    if (!isNameValid || !isEmailValid || !isPasswordValid || !isConfirmPasswordValid) {
      return;
    }

    this.setLoading(true);

    try {
      // Préparer les données d'enregistrement
      const registerData: RegisterDto = {
        nom: this.nameInput.value.trim(),
        email: this.emailInput.value.trim(),
        password: this.passwordInput.value,
        confirmPassword: this.confirmPasswordInput.value
      };

      // Appeler AuthService.register()
      this.authService.register(registerData).subscribe({
        next: (response: any) => {
          console.log('Sign up successful:', response.user.email);
          this.showGentleSuccess();
          
          // Redirection après l'animation
          setTimeout(() => {
            this.router.navigate(['/dashboard']);
          }, 3500);
        },
        error: (error: any) => {
          console.error('Sign up error:', error);
          this.showError('email', error.message || 'Inscription échouée. Veuillez réessayer.');
          this.setLoading(false);
        }
      });
    } catch (error) {
      console.error('Unexpected error:', error);
      this.showError('email', 'Inscription échouée. Veuillez réessayer.');
      this.setLoading(false);
    }
  }

  private setLoading(loading: boolean): void {
    this.submitButton.classList.toggle('loading', loading);
    this.submitButton.disabled = loading;
  }

  private showGentleSuccess(): void {
    this.form.style.transform = 'scale(0.95)';
    this.form.style.opacity = '0';
    this.form.style.filter = 'blur(1px)';

    setTimeout(() => {
      this.form.style.display = 'none';
      this.successMessage.classList.add('show');
    }, 300);

    setTimeout(() => {
      console.log('Welcome! Your account has been created.');
    }, 3500);
  }
}
