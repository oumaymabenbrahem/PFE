import { Component } from '@angular/core';
import { ScriptService } from '../../../core/services/script.service';

@Component({
  selector: 'app-test-scripts',
  templateUrl: './test-scripts.component.html',
  styleUrls: ['./test-scripts.component.scss']
})
export class TestScriptsComponent {
  frameworks: string[] = ['Selenium', 'Cypress', 'Playwright', 'Puppeteer', 'Cucumber', 'Pytest (API)'];
  languages: string[] = ['Python', 'Java', 'JavaScript', 'TypeScript'];

  selectedFramework = 'Selenium';
  selectedLanguage = 'Python';

  scenarioInput = '';

  generatedFileName = 'test_scenario.py';
  generatedCode = '';
  isGenerating = false;
  errorMessage = '';
  copySuccess = false;

  get codeLinesCount(): number {
    return this.generatedCode.split('\n').length;
  }

  constructor(private scriptService: ScriptService) {}

  generateScript(): void {
    if (!this.scenarioInput.trim()) {
      this.errorMessage = 'Veuillez saisir un texte de scénario avant de générer.';
      return;
    }

    this.isGenerating = true;
    this.errorMessage = '';
    this.generatedCode = '';
    this.generatedFileName = '';

    this.scriptService.generateTestScript({
      scenario_text: this.scenarioInput,
      framework: this.selectedFramework,
      language: this.selectedLanguage
    }).subscribe({
      next: (response) => {
        this.generatedCode = response.generated_code;
        this.generatedFileName = response.file_name;
        this.isGenerating = false;
      },
      error: (err) => {
        this.errorMessage = err.message || 'Erreur lors de la génération du script.';
        this.isGenerating = false;
      }
    });
  }

  copyCode(): void {
    if (!this.generatedCode) return;

    navigator.clipboard.writeText(this.generatedCode).then(() => {
      this.copySuccess = true;
      setTimeout(() => {
        this.copySuccess = false;
      }, 2000);
    }).catch(() => {
      // Fallback for older browsers
      const textarea = document.createElement('textarea');
      textarea.value = this.generatedCode;
      document.body.appendChild(textarea);
      textarea.select();
      document.execCommand('copy');
      document.body.removeChild(textarea);
      this.copySuccess = true;
      setTimeout(() => {
        this.copySuccess = false;
      }, 2000);
    });
  }

  exportScript(): void {
    if (!this.generatedCode) return;

    const blob = new Blob([this.generatedCode], { type: 'text/plain' });
    const url = window.URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = this.generatedFileName || 'test_script.py';
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    window.URL.revokeObjectURL(url);
  }
}
