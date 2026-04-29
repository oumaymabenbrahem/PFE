import { Component, OnInit, AfterViewInit } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from '../../../core/services/auth.service';
import { LoginDto } from '../../../shared/models/user.model';

@Component({
  selector: 'app-sign-in',
  templateUrl: './sign-in.component.html',
  styleUrls: ['./sign-in.component.scss']
})
export class SignInComponent implements OnInit, AfterViewInit {
  private form!: HTMLFormElement;
  private emailInput!: HTMLInputElement;
  private passwordInput!: HTMLInputElement;
  private passwordToggle!: HTMLButtonElement;
  private submitButton!: HTMLButtonElement;
  private successMessage!: HTMLElement;
  private socialButtons!: NodeListOf<HTMLButtonElement>;

  constructor(
    private authService: AuthService,
    private router: Router
  ) { }

  ngOnInit(): void {
  }

  ngAfterViewInit(): void {
    this.initializeForm();
    this.bindEvents();
    this.setupPasswordToggle();
    this.setupSocialButtons();
    this.setupGentleEffects();
  }

  private initializeForm(): void {
    this.form = document.getElementById('loginForm') as HTMLFormElement;
    this.emailInput = document.getElementById('email') as HTMLInputElement;
    this.passwordInput = document.getElementById('password') as HTMLInputElement;
    this.passwordToggle = document.getElementById('passwordToggle') as HTMLButtonElement;
    this.submitButton = this.form.querySelector('.comfort-button') as HTMLButtonElement;
    this.successMessage = document.getElementById('successMessage') as HTMLElement;
    this.socialButtons = document.querySelectorAll('.social-soft');
  }

  private bindEvents(): void {
    this.form.addEventListener('submit', (e) => this.handleSubmit(e));
    this.emailInput.addEventListener('blur', () => this.validateEmail());
    this.passwordInput.addEventListener('blur', () => this.validatePassword());
    this.emailInput.addEventListener('input', () => this.clearError('email'));
    this.passwordInput.addEventListener('input', () => this.clearError('password'));

    this.emailInput.setAttribute('placeholder', ' ');
    this.passwordInput.setAttribute('placeholder', ' ');

    // Setup floating labels
    this.setupFloatingLabels();
  }

  private setupFloatingLabels(): void {
    [this.emailInput, this.passwordInput].forEach(input => {
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

  private setupPasswordToggle(): void {
    this.passwordToggle.addEventListener('click', () => {
      const type = this.passwordInput.type === 'password' ? 'text' : 'password';
      this.passwordInput.type = type;
      this.passwordToggle.classList.toggle('toggle-active', type === 'text');
      this.triggerGentleRipple(this.passwordToggle);
    });
  }

  private setupSocialButtons(): void {
    this.socialButtons.forEach(button => {
      button.addEventListener('click', (e) => {
        const provider = (button.querySelector('span') as HTMLElement).textContent?.trim() || '';
        this.handleSocialLogin(provider, button);
      });
    });
  }

  private setupGentleEffects(): void {
    [this.emailInput, this.passwordInput].forEach(input => {
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

  private triggerGentleRipple(element: HTMLElement): void {
    element.style.transform = 'scale(0.95)';
    setTimeout(() => {
      element.style.transform = 'scale(1)';
    }, 150);
  }

  private addGentleClickEffects(): void {
    const interactiveElements = document.querySelectorAll('.comfort-button, .social-soft, .gentle-checkbox');

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
      this.showError('password', 'Veuillez saisir votre mot de passe');
      return false;
    }

    if (password.length < 6) {
      this.showError('password', 'Le mot de passe doit contenir au moins 6 caractères');
      return false;
    }

    this.clearError('password');
    return true;
  }

  private showError(field: string, message: string): void {
    const softField = document.getElementById(field)?.closest('.soft-field') as HTMLElement;
    const errorElement = document.getElementById(`${field}Error`) as HTMLElement;

    if (softField && errorElement) {
      softField.classList.add('error');
      errorElement.textContent = message;
      errorElement.classList.add('show');
      this.triggerGentleShake(softField);
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

  private triggerGentleShake(element: HTMLElement): void {
    element.style.animation = 'none';
    element.style.transform = 'translateX(2px)';

    setTimeout(() => {
      element.style.transform = 'translateX(-2px)';
    }, 100);

    setTimeout(() => {
      element.style.transform = 'translateX(0)';
    }, 200);
  }

  private showGentleFailure(): void {
    const logoCircle = document.querySelector('.logo-circle') as HTMLElement;
    if (logoCircle) {
      logoCircle.classList.add('login-failure');
    }
  }

  private resetLoginState(): void {
    const logoCircle = document.querySelector('.logo-circle') as HTMLElement;
    if (logoCircle) {
      logoCircle.classList.remove('login-success', 'login-failure');
    }
  }

  private async handleSubmit(e: Event): Promise<void> {
    e.preventDefault();

    const isEmailValid = this.validateEmail();
    const isPasswordValid = this.validatePassword();

    if (!isEmailValid || !isPasswordValid) {
      return;
    }

    this.setLoading(true);

    try {
      // Préparer les credentials
      const credentials: LoginDto = {
        email: this.emailInput.value.trim(),
        password: this.passwordInput.value
      };

      // Appeler AuthService.login()
      this.authService.login(credentials).subscribe({
        next: (response: any) => {
          console.log('Login successful:', response.user.email);
          this.showGentleSuccess();
          
          // Redirection après l'animation
          setTimeout(() => {
            this.router.navigate(['/dashboard']);
          }, 3500);
        },
        error: (error: any) => {
          console.error('Login error:', error);
          this.showGentleFailure();
          this.showError('password', error.message || 'Connexion échouée. Veuillez réessayer.');
          this.setLoading(false);
          setTimeout(() => {
            this.resetLoginState();
          }, 1500);
        }
      });
    } catch (error) {
      console.error('Unexpected error:', error);
      this.showError('password', 'Connexion échouée. Veuillez réessayer.');
      this.setLoading(false);
    }
  }

  private async handleSocialLogin(provider: string, button: HTMLElement): Promise<void> {
    console.log(`Signing in with ${provider}...`);

    const originalHTML = button.innerHTML;
    button.style.pointerEvents = 'none';
    button.style.opacity = '0.7';

    const loadingHTML = `
      <div class="social-background"></div>
      <div class="gentle-spinner">
        <div class="spinner-circle"></div>
      </div>
      <span>Connexion...</span>
      <div class="social-glow"></div>
    `;

    button.innerHTML = loadingHTML;

    try {
      await new Promise(resolve => setTimeout(resolve, 2000));
      console.log(`Redirecting to ${provider}...`);
    } catch (error) {
      console.error(`${provider} sign in error:`, error);
    } finally {
      button.style.pointerEvents = 'auto';
      button.style.opacity = '1';
      button.innerHTML = originalHTML;
    }
  }

  private setLoading(loading: boolean): void {
    this.submitButton.classList.toggle('loading', loading);
    this.submitButton.disabled = loading;

    this.socialButtons.forEach(button => {
      button.style.pointerEvents = loading ? 'none' : 'auto';
      button.style.opacity = loading ? '0.5' : '1';
    });
  }

  private showGentleSuccess(): void {
    const logoCircle = document.querySelector('.logo-circle') as HTMLElement;
    if (logoCircle) {
      logoCircle.classList.add('login-success');
    }

    this.form.style.transform = 'scale(0.95)';
    this.form.style.opacity = '0';
    this.form.style.filter = 'blur(1px)';

    setTimeout(() => {
      this.form.style.display = 'none';
      const socialDiv = document.querySelector('.comfort-social') as HTMLElement;
      const signupDiv = document.querySelector('.comfort-signup') as HTMLElement;
      const dividerDiv = document.querySelector('.gentle-divider') as HTMLElement;

      if (socialDiv) socialDiv.style.display = 'none';
      if (signupDiv) signupDiv.style.display = 'none';
      if (dividerDiv) dividerDiv.style.display = 'none';

      this.successMessage.classList.add('show');
      this.triggerSuccessGlow();
    }, 300);

    setTimeout(() => {
      console.log('Welcome! Taking you to your dashboard...');
    }, 3500);
  }

  private triggerSuccessGlow(): void {
    const card = document.querySelector('.soft-card') as HTMLElement;
    if (card) {
      card.style.boxShadow = `
        0 20px 40px rgba(240, 206, 170, 0.2),
        0 8px 24px rgba(240, 206, 170, 0.15),
        inset 0 1px 0 rgba(255, 255, 255, 0.8)
      `;

      setTimeout(() => {
        card.style.boxShadow = `
          0 20px 40px rgba(0, 0, 0, 0.03),
          0 8px 24px rgba(0, 0, 0, 0.02),
          inset 0 1px 0 rgba(255, 255, 255, 0.8)
        `;
      }, 2000);
    }
  }
}
