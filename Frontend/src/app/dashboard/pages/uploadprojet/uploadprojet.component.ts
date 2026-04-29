import { Component, OnInit } from '@angular/core';
import { FormBuilder, FormGroup, Validators } from '@angular/forms';
import { ProjectService } from '../../../core/services/project.service';
import { ProjectRequest, ProjectResponse } from '../../../shared/models/project.model';
import { Router } from '@angular/router';

@Component({
  selector: 'app-uploadprojet',
  templateUrl: './uploadprojet.component.html',
  styleUrls: ['./uploadprojet.component.scss']
})
export class UploadprojetComponent implements OnInit {
  showForm = false;
  projectForm: FormGroup;
  selectedFiles: File[] = [];
  isDragOver = false;
  isLoading = false;
  successMessage = '';
  errorMessage = '';

  constructor(
    private fb: FormBuilder,
    private projectService: ProjectService,
    private router: Router
  ) {
    this.projectForm = this.fb.group({
      nomProjet: ['', [Validators.required, Validators.minLength(3)]],
      description: [''],
      specificationType: ['USER_STORY', Validators.required],
      userStory: [''],
      urlGithub: [''],
      urlApplication: [''],
      focusOptionnel: [''],
      zipFile: [null]
    });
  }

  ngOnInit(): void {
    // À faire : initialiser les données si nécessaire
  }

  toggleForm() {
    this.showForm = !this.showForm;
    this.clearMessages();
  }

  onFileSelected(event: any) {
    const files = event.target.files;
    this.handleFiles(files);
  }

  onDragOver(event: DragEvent) {
    event.preventDefault();
    this.isDragOver = true;
  }

  onDragLeave(event: DragEvent) {
    event.preventDefault();
    this.isDragOver = false;
  }

  onDrop(event: DragEvent) {
    event.preventDefault();
    this.isDragOver = false;
    const files = event.dataTransfer?.files;
    if (files) {
      this.handleFiles(files);
    }
  }

  private handleFiles(files: FileList) {
    this.clearMessages();
    
    for (let i = 0; i < files.length; i++) {
      const file = files[i];
      // Accepter la plupart des formats de fichier (archives, code, config, doc, etc.)
      const allowedExtensions = [
        // Archives
        '.zip', '.rar', '.7z', '.tar', '.gz', '.bz2',
        // Code source - Web
        '.html', '.htm', '.css', '.scss', '.less', '.js', '.jsx', '.ts', '.tsx',
        // Code source - Backend
        '.java', '.class', '.jar', '.war', '.py', '.pyw', '.c', '.cpp', '.cc', '.cxx', '.cs',
        '.go', '.rb', '.php', '.swift', '.kt', '.rs', '.r', '.pl', '.lua', '.sh', '.bash',
        // Langages de templating
        '.jsx', '.tsx', '.vue', '.svelte', '.hbs', '.ejs',
        // Configuration
        '.json', '.xml', '.yaml', '.yml', '.toml', '.ini', '.conf', '.config',
        '.properties', '.env', '.gradle', '.maven',
        // Documentation
        '.txt', '.md', '.markdown', '.rst', '.asciidoc', '.pdf', '.doc', '.docx',
        // Données
        '.sql', '.db', '.csv', '.xlsx', '.xls',
        // Autres
        '.exe', '.dll', '.so', '.a', '.o', '.obj'
      ];

      // Vérifier l'extension du fichier
      const fileExtension = '.' + file.name.split('.').pop()?.toLowerCase();
      const isAllowedType = allowedExtensions.includes(fileExtension);

      if (isAllowedType) {
        // Éviter les doublons
        if (!this.selectedFiles.find(f => f.name === file.name)) {
          this.selectedFiles.push(file);
          this.projectForm.patchValue({ zipFile: file });
        }
      } else {
        this.errorMessage = `Type de fichier non autorisé: ${file.name}. Formats acceptés: archives (.zip, .rar, .7z), code source (.java, .py, .js, .ts, etc.), configuration, documentation et plus.`;
      }
    }
  }

  removeFile(index: number) {
    this.selectedFiles.splice(index, 1);
    if (this.selectedFiles.length === 0) {
      this.projectForm.patchValue({ zipFile: null });
    }
  }

  onSubmit() {
    this.clearMessages();
    
    // Vérifier que le formulaire est valide
    if (this.projectForm.invalid) {
      this.markFormGroupTouched();
      this.errorMessage = 'Veuillez corriger les erreurs du formulaire';
      return;
    }

    const specificationType = this.projectForm.get('specificationType')?.value;
    let specificationContenu = '';
    let fichierFile = null;

    // Valider selon le type de spécification
    if (specificationType === 'USER_STORY') {
      specificationContenu = this.projectForm.get('userStory')?.value?.trim();
      if (!specificationContenu) {
        this.errorMessage = 'Veuillez saisir une user story';
        return;
      }
    } else if (specificationType === 'LIEN_GIT') {
      specificationContenu = this.projectForm.get('urlGithub')?.value?.trim();
      if (!specificationContenu) {
        this.errorMessage = 'Veuillez fournir une URL GitHub';
        return;
      }
      if (!/^https:\/\/github\.com\/.+$/.test(specificationContenu)) {
        this.errorMessage = 'L\'URL GitHub n\'est pas valide';
        return;
      }
    } else if (specificationType === 'LIEN_APPLICATION') {
      specificationContenu = this.projectForm.get('urlApplication')?.value?.trim();
      if (!specificationContenu) {
        this.errorMessage = 'Veuillez fournir une URL d\'application';
        return;
      }
      if (!/^https?:\/\/.+/.test(specificationContenu)) {
        this.errorMessage = 'L\'URL d\'application n\'est pas valide';
        return;
      }
    } else if (specificationType === 'CODE_FICHIER') {
      if (this.selectedFiles.length === 0) {
        this.errorMessage = 'Veuillez sélectionner un fichier';
        return;
      }
      fichierFile = this.selectedFiles[0];
    }

    // Construire le ProjectRequest
    const projectRequest: ProjectRequest = {
      nom: this.projectForm.get('nomProjet')?.value,
      description: this.projectForm.get('description')?.value || '',
      specificationType: specificationType,
      specificationContenu: specificationContenu || undefined,
      focusOptionnel: specificationType === 'LIEN_APPLICATION' ? this.projectForm.get('focusOptionnel')?.value?.trim() : undefined
    };

    // Construire le FormData
    const formData = new FormData();
    
    // Ajouter la requête comme Blob JSON
    const requestBlob = new Blob(
      [JSON.stringify(projectRequest)],
      { type: 'application/json' }
    );
    formData.append('project', requestBlob);

    // Ajouter le fichier s'il existe
    if (fichierFile) {
      formData.append('fichier', fichierFile);
    }

    // Envoyer au serveur
    this.isLoading = true;
    this.projectService.createProject(formData).subscribe({
      next: (response: ProjectResponse) => {
        this.isLoading = false;
        this.successMessage = `Projet "${response.nom}" créé avec succès!`;
        this.resetForm();
        
        // Attendre 2 secondes avant de rediriger
        setTimeout(() => {
          this.router.navigate(['/projects']);
        }, 2000);
      },
      error: (error: Error) => {
        this.isLoading = false;
        this.errorMessage = error.message;
        console.error('Erreur complète:', error);
      }
    });
  }

  onSpecificationTypeChange(): void {
    // Réinitialiser les champs selon le type sélectionné
    const type = this.projectForm.get('specificationType')?.value;
    
    if (type === 'USER_STORY') {
      this.projectForm.patchValue({ userStory: '', urlGithub: '', urlApplication: '', focusOptionnel: '' });
      this.selectedFiles = [];
    } else if (type === 'LIEN_GIT') {
      this.projectForm.patchValue({ userStory: '', urlGithub: '', urlApplication: '', focusOptionnel: '' });
      this.selectedFiles = [];
    } else if (type === 'LIEN_APPLICATION') {
      this.projectForm.patchValue({ userStory: '', urlGithub: '', urlApplication: '', focusOptionnel: '' });
      this.selectedFiles = [];
    } else if (type === 'CODE_FICHIER') {
      this.projectForm.patchValue({ userStory: '', urlGithub: '', urlApplication: '', focusOptionnel: '' });
    }
  }

  cancel() {
    this.showForm = false;
    this.resetForm();
    this.clearMessages();
  }

  private clearMessages() {
    this.successMessage = '';
    this.errorMessage = '';
  }

  private markFormGroupTouched() {
    Object.keys(this.projectForm.controls).forEach(key => {
      const control = this.projectForm.get(key);
      control?.markAsTouched();
    });
  }

  private resetForm() {
    this.projectForm.reset();
    this.selectedFiles = [];
  }

  getErrorMessage(fieldName: string): string {
    const control = this.projectForm.get(fieldName);
    if (control?.hasError('required')) {
      return 'Ce champ est requis';
    }
    if (control?.hasError('minlength')) {
      return `Minimum ${control.errors?.['minlength'].requiredLength} caractères`;
    }
    if (control?.hasError('pattern')) {
      return 'URL GitHub invalide. Format: https://github.com/username/repository';
    }
    return '';
  }
}


