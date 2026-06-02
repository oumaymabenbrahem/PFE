import { Component, OnInit, OnDestroy } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from '../../../core/services/auth.service';
import { ProjectService } from '../../../core/services/project.service';
import { User } from '../../../shared/models/user.model';
import { ProjectResponse } from '../../../shared/models/project.model';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';

interface ProfileStat {
  value: string;
  label: string;
}

@Component({
  selector: 'app-user-profile',
  templateUrl: './user-profile.component.html',
  styleUrls: ['./user-profile.component.scss']
})
export class UserProfileComponent implements OnInit, OnDestroy {
  user: User | null = null;
  profileStats: ProfileStat[] = [
    { value: '0', label: 'Tests générés' },
    { value: '0', label: 'Scripts actifs' },
    { value: '0', label: 'Projets' },
    { value: '0%', label: 'Taux de succès' }
  ];
  showUpdateModal = false;
  updateProfileStep = 2;
  updatedName = '';
  updatedEmail = '';
  updatedOrganization = 'TEST2i';
  showDeleteConfirmModal = false;
  private destroy$ = new Subject<void>();

  constructor(
    private auth: AuthService,
    private projectService: ProjectService,
    private router: Router
  ) {}

  ngOnInit(): void {
    this.auth.currentUser$.pipe(takeUntil(this.destroy$)).subscribe((u: User | null) => {
      this.user = u;
      this.updatedName = u?.nom || '';
      this.updatedEmail = u?.email || '';
      this.updatedOrganization = 'TEST2i';
    });
    this.loadProfileStats();
  }

  openUpdateProfileModal(): void {
    if (!this.user) {
      return;
    }
    this.updateProfileStep = 2;
    this.updatedName = this.user.nom || '';
    this.updatedEmail = this.user.email || '';
    this.updatedOrganization = 'TEST2i';
    this.showUpdateModal = true;
  }

  closeUpdateProfileModal(): void {
    this.showUpdateModal = false;
  }

  goToStep(step: number): void {
    this.updateProfileStep = step;
  }

  saveProfileChanges(): void {
    if (!this.user) {
      return;
    }

    this.auth.updateProfile({ email: this.updatedEmail, nom: this.updatedName }).subscribe({
      next: () => {
        this.user = {
          ...this.user!,
          nom: this.updatedName,
          email: this.updatedEmail
        };
        this.closeUpdateProfileModal();
      },
      error: (error: Error) => {
        console.error('Impossible de sauvegarder le profil:', error.message);
      }
    });
  }

  openDeleteConfirmModal(): void {
    this.showDeleteConfirmModal = true;
  }

  closeDeleteConfirmModal(): void {
    this.showDeleteConfirmModal = false;
  }

  confirmDeleteAccount(): void {
    if (!this.user) {
      return;
    }

    this.auth.deleteAccount().subscribe({
      next: () => {
        this.auth.logout();
        this.router.navigate(['/']);
      },
      error: (error: Error) => {
        console.error('Erreur lors de la suppression du compte:', error.message);
      }
    });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  get primaryRole(): string | null {
    if (!this.user || !this.user.roles || this.user.roles.length === 0) return null;
    return this.user.roles[0];
  }

  getInitials(): string {
    if (!this.user || !this.user.nom) return 'U';
    const parts = this.user.nom.trim().split(/\s+/);
    return parts.length === 1 ? parts[0].charAt(0).toUpperCase() : (parts[0].charAt(0) + parts[parts.length-1].charAt(0)).toUpperCase();
  }

  private loadProfileStats(): void {
    this.projectService.getMyProjects().pipe(takeUntil(this.destroy$)).subscribe({
      next: (projects: ProjectResponse[]) => {
        const totalProjects = projects.length;
        const totalScripts = projects.reduce((sum, project) => sum + Number(project.nombreScripts || 0), 0);
        const activeScripts = projects
          .filter(project => project.statut !== 'TERMINE')
          .reduce((sum, project) => sum + Number(project.nombreScripts || 0), 0);
        const finishedProjects = projects.filter(project => project.statut === 'TERMINE').length;
        const successRate = totalProjects > 0 ? Math.round((finishedProjects / totalProjects) * 100) : 0;

        this.profileStats = [
          { value: totalScripts.toString(), label: 'Tests générés' },
          { value: activeScripts.toString(), label: 'Scripts actifs' },
          { value: totalProjects.toString(), label: 'Projets' },
          { value: `${successRate}%`, label: 'Taux de succès' }
        ];
      },
      error: () => {
        this.profileStats = [
          { value: '0', label: 'Tests générés' },
          { value: '0', label: 'Scripts actifs' },
          { value: '0', label: 'Projets' },
          { value: '0%', label: 'Taux de succès' }
        ];
      }
    });
  }
}
