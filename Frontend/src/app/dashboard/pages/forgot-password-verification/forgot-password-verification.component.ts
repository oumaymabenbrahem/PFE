import { Component, ElementRef, OnDestroy, OnInit, QueryList, ViewChildren } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { AuthService } from '../../../core/services/auth.service';

@Component({
  selector: 'app-forgot-password-verification',
  templateUrl: './forgot-password-verification.component.html',
  styleUrls: ['./forgot-password-verification.component.scss']
})
export class ForgotPasswordVerificationComponent implements OnInit, OnDestroy {
  @ViewChildren('codeInput') codeInputs!: QueryList<ElementRef<HTMLInputElement>>;

  emailMask = 'v***@exemple.com';
  private email = '';
  codeDigits = Array(6).fill('');
  verificationError = '';
  isLoading = false;

  // Timer (10 minutes = 600 seconds)
  timerSeconds = 600;
  timerDisplay = '10:00';
  timerExpired = false;
  private timerInterval: any;

  // Resend cooldown (60 seconds between resends)
  resendCooldown = 0;
  resendDisplay = '';
  canResend = true;
  isResending = false;
  resendCount = 0;
  maxResend = 3;
  private resendInterval: any;

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private authService: AuthService
  ) {}

  ngOnInit(): void {
    this.email = this.route.snapshot.queryParamMap.get('email') || '';
    if (this.email) {
      this.emailMask = this.maskEmail(this.email);
    }
    this.startTimer();
  }

  ngOnDestroy(): void {
    this.clearTimers();
  }

  private startTimer(): void {
    this.timerSeconds = 600;
    this.timerExpired = false;
    this.updateTimerDisplay();

    this.timerInterval = setInterval(() => {
      this.timerSeconds--;
      this.updateTimerDisplay();

      if (this.timerSeconds <= 0) {
        this.timerExpired = true;
        clearInterval(this.timerInterval);
      }
    }, 1000);
  }

  private updateTimerDisplay(): void {
    const minutes = Math.floor(this.timerSeconds / 60);
    const seconds = this.timerSeconds % 60;
    this.timerDisplay = `${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}`;
  }

  private startResendCooldown(): void {
    this.resendCooldown = 60;
    this.canResend = false;
    this.updateResendDisplay();

    this.resendInterval = setInterval(() => {
      this.resendCooldown--;
      this.updateResendDisplay();

      if (this.resendCooldown <= 0) {
        this.canResend = true;
        clearInterval(this.resendInterval);
      }
    }, 1000);
  }

  private updateResendDisplay(): void {
    this.resendDisplay = `(${this.resendCooldown}s)`;
  }

  private clearTimers(): void {
    if (this.timerInterval) clearInterval(this.timerInterval);
    if (this.resendInterval) clearInterval(this.resendInterval);
  }

  resendCode(): void {
    if (!this.canResend || this.isResending || !this.email) return;

    if (this.resendCount >= this.maxResend) {
      this.verificationError = 'Nombre maximum de renvois atteint. Veuillez réessayer dans 15 minutes.';
      return;
    }

    this.isResending = true;
    this.verificationError = '';

    this.authService.forgotPassword(this.email).subscribe({
      next: () => {
        this.isResending = false;
        this.resendCount++;
        // Reset the timer
        clearInterval(this.timerInterval);
        this.startTimer();
        // Start cooldown
        this.startResendCooldown();
        // Clear previous input
        this.codeDigits = Array(6).fill('');
        this.codeInputs.forEach(input => input.nativeElement.value = '');
        this.focusInput(0);
      },
      error: (err: Error) => {
        this.isResending = false;
        this.verificationError = err.message || 'Impossible de renvoyer le code.';
      }
    });
  }

  private maskEmail(email: string): string {
    const [localPart, domainPart] = email.split('@');
    if (!localPart || !domainPart) {
      return 'v***@exemple.com';
    }

    const first = localPart.charAt(0);
    return `${first}***@${domainPart}`;
  }

  onDigitKeydown(event: KeyboardEvent, index: number): void {
    const input = event.target as HTMLInputElement;

    const allowedControlKeys = ['Backspace', 'Tab', 'Delete', 'ArrowLeft', 'ArrowRight', 'Home', 'End'];
    if (!allowedControlKeys.includes(event.key) && !/^\d$/.test(event.key)) {
      event.preventDefault();
      return;
    }

    if (event.key === 'Backspace' && !input.value && index > 0) {
      this.focusInput(index - 1);
      return;
    }

    if (event.key === 'ArrowLeft' && index > 0) {
      event.preventDefault();
      this.focusInput(index - 1);
      return;
    }

    if (event.key === 'ArrowRight' && index < this.codeDigits.length - 1) {
      event.preventDefault();
      this.focusInput(index + 1);
    }
  }

  onDigitBeforeInput(event: InputEvent, index: number): void {
    const input = event.target as HTMLInputElement;
    const digit = (event.data || '').replace(/\D/g, '').slice(-1);

    if (!digit) {
      event.preventDefault();
      return;
    }

    event.preventDefault();
    this.codeDigits[index] = digit;
    input.value = digit;

    if (index < this.codeDigits.length - 1) {
      setTimeout(() => this.focusInput(index + 1), 0);
    }
  }

  onDigitKeyup(event: KeyboardEvent, index: number): void {
    if (!/^\d$/.test(event.key)) {
      return;
    }

    if (index < this.codeDigits.length - 1) {
      setTimeout(() => this.focusInput(index + 1), 0);
    }
  }

  onPaste(event: ClipboardEvent): void {
    event.preventDefault();
    const pasted = event.clipboardData?.getData('text') || '';
    const digits = pasted.replace(/\D/g, '').slice(0, this.codeDigits.length).split('');

    if (!digits.length) {
      return;
    }

    digits.forEach((digit, i) => {
      this.codeDigits[i] = digit;
      const field = this.codeInputs.get(i)?.nativeElement;
      if (field) {
        field.value = digit;
      }
    });

    const nextIndex = Math.min(digits.length, this.codeDigits.length - 1);
    this.focusInput(nextIndex);
  }

  verifyCode(): void {
    this.verificationError = '';
    const enteredCode = this.codeDigits.join('');

    if (enteredCode.length < this.codeDigits.length) {
      this.verificationError = 'Veuillez saisir les 6 chiffres du code.';
      return;
    }

    if (this.timerExpired) {
      this.verificationError = 'Le code a expiré. Veuillez en demander un nouveau.';
      return;
    }

    this.isLoading = true;

    this.authService.verifyOtp(this.email, enteredCode).subscribe({
      next: (response: any) => {
        this.isLoading = false;
        // Naviguer vers la page de reset avec le resetToken
        this.router.navigate(['/reset-password'], {
          queryParams: {
            token: response.resetToken,
            email: this.email
          }
        });
      },
      error: (err: Error) => {
        this.isLoading = false;
        this.verificationError = err.message || 'Le code saisi est incorrect.';
      }
    });
  }

  private focusInput(index: number): void {
    const field = this.codeInputs.get(index)?.nativeElement;
    if (field) {
      field.focus();
      field.select();
    }
  }
}
