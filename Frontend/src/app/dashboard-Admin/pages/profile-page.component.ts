import { CommonModule } from '@angular/common';
import { Component, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { forkJoin } from 'rxjs';
import { PlatformUser, UserService } from '../../core/services/user.service';

@Component({
  selector: 'app-profile-page',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <section class="users-inbox" aria-label="Liste des utilisateurs inscrits">
      <header class="inbox-toolbar">
        <div class="toolbar-actions" aria-label="Actions utilisateurs">
          <label class="select-action" title="Sélectionner les utilisateurs affichés">
            <span class="custom-check">
              <input
                type="checkbox"
                [checked]="allVisibleSelected"
                [disabled]="pagedUsers.length === 0"
                (change)="toggleVisibleUsers($event)"
                aria-label="Sélectionner les utilisateurs affichés"
              />
              <span></span>
            </span>
            <i class="bi bi-chevron-down"></i>
          </label>

          <button type="button" class="icon-action" (click)="loadUsers()" [disabled]="isLoading" aria-label="Rafraîchir">
            <i class="bi bi-arrow-clockwise"></i>
          </button>
          <button type="button" class="icon-action" (click)="openEditDialog()" [disabled]="selectedUserIds.size !== 1 || isLoading" aria-label="Modifier l'utilisateur" title="Modifier l'utilisateur">
            <i class="bi bi-pencil-square"></i>
          </button>
          <button type="button" class="icon-action danger" (click)="openDeleteDialog()" [disabled]="selectedUserIds.size === 0 || isLoading || isDeleting" aria-label="Supprimer la sélection" title="Supprimer la sélection">
            <i class="bi bi-trash"></i>
          </button>
        </div>

        <label class="search-field">
          <i class="bi bi-search"></i>
          <input
            type="text"
            [ngModel]="searchTerm"
            (ngModelChange)="onSearchChange($event)"
            placeholder="Search..."
            aria-label="Rechercher un utilisateur"
          />
        </label>
      </header>

      <div class="state" *ngIf="isLoading">Chargement des utilisateurs...</div>
      <div class="state error" *ngIf="!isLoading && errorMessage">{{ errorMessage }}</div>
      <div class="state" *ngIf="!isLoading && !errorMessage && filteredUsers.length === 0">Aucun utilisateur trouvé.</div>

      <div class="inbox-list" *ngIf="!isLoading && !errorMessage && filteredUsers.length > 0">
        <article
          class="inbox-row"
          *ngFor="let user of pagedUsers"
          [class.selected]="isSelected(user.id)"
        >
          <span class="custom-check row-check">
            <input
              type="checkbox"
              [checked]="isSelected(user.id)"
              (change)="toggleUserSelection(user.id, $event)"
              [attr.aria-label]="'Sélectionner ' + user.nom"
            />
            <span></span>
          </span>

          <button type="button" class="star-button" [attr.aria-label]="'Marquer ' + user.nom">
            <i class="bi bi-star"></i>
          </button>

          <strong class="sender">{{ user.nom }}</strong>
          <span class="message-preview">{{ user.email }}</span>

          <span class="role-badge" [class.admin]="isAdmin(user)" [class.empty]="!getPrimaryRole(user)">
            {{ getPrimaryRole(user) || 'No role' }}
          </span>

          <time class="date-label">{{ formatDate(user.createdAt) }}</time>
        </article>
      </div>

      <footer class="inbox-footer" *ngIf="!isLoading && !errorMessage">
        <span class="footer-count">{{ showingLabel }}</span>
        <div class="pager-actions centered">
          <button type="button" class="pager-btn" (click)="previousPage()" [disabled]="currentPage === 1" aria-label="Page précédente">
            <i class="bi bi-chevron-left"></i>
          </button>
          <button type="button" class="pager-btn" (click)="nextPage()" [disabled]="currentPage === totalPages" aria-label="Page suivante">
            <i class="bi bi-chevron-right"></i>
          </button>
        </div>
      </footer>

      <div class="delete-modal-backdrop" *ngIf="showDeleteDialog" (click)="cancelDeleteDialog()" aria-hidden="true"></div>
      <section
        class="delete-modal"
        *ngIf="showDeleteDialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="deleteDialogTitle"
      >
        <div class="delete-modal-header">
          <div class="delete-modal-icon">
            <i class="bi bi-exclamation-triangle-fill"></i>
          </div>
          <div>
            <h3 id="deleteDialogTitle">Supprimer les utilisateurs sélectionnés</h3>
            <p>
              {{ selectedUserIds.size }} utilisateur{{ selectedUserIds.size > 1 ? 's' : '' }} sera{{ selectedUserIds.size > 1 ? 'ont' : 'a' }} supprimé{{ selectedUserIds.size > 1 ? 's' : '' }} définitivement.
            </p>
          </div>
        </div>

        <div class="delete-modal-body">
          <span class="warning-chip"><i class="bi bi-shield-exclamation"></i> Action irréversible</span>
          <p>
            Cette action supprimera les comptes, les accès associés et toutes les données visibles dans cette liste.
          </p>
        </div>

        <div class="delete-modal-actions">
          <button type="button" class="modal-btn cancel" (click)="cancelDeleteDialog()" [disabled]="isDeleting">
            Annuler
          </button>
          <button type="button" class="modal-btn danger" (click)="confirmDeleteSelectedUsers()" [disabled]="isDeleting">
            <span *ngIf="!isDeleting"><i class="bi bi-trash me-2"></i>Supprimer</span>
            <span *ngIf="isDeleting"><i class="bi bi-arrow-repeat spin me-2"></i>Suppression...</span>
          </button>
        </div>
      </section>

      <!-- Modal de Modification -->
      <div class="delete-modal-backdrop" *ngIf="showEditDialog" (click)="cancelEditDialog()" aria-hidden="true"></div>
      <section class="delete-modal edit-modal" *ngIf="showEditDialog" role="dialog" aria-modal="true" aria-labelledby="editDialogTitle">
        <div class="delete-modal-header">
          <div class="delete-modal-icon edit-icon">
            <i class="bi bi-pencil-square"></i>
          </div>
          <div>
            <h3 id="editDialogTitle">Modifier l'utilisateur</h3>
            <p>Mettez à jour les informations de {{ editingUser?.nom }}</p>
          </div>
        </div>

        <div class="delete-modal-body edit-body">
          <div class="form-group mb-3">
            <label class="form-label">Nom complet</label>
            <input type="text" class="form-control admin-input" [(ngModel)]="editUserData.nom" placeholder="Nom complet">
          </div>
          <div class="form-group mb-3">
            <label class="form-label">Email</label>
            <input type="email" class="form-control admin-input" [(ngModel)]="editUserData.email" placeholder="Email">
          </div>
          <div class="form-group">
            <label class="form-label">Rôle d'accès</label>
            <div class="role-selector-wrapper">
              <select class="form-control admin-input role-select" [(ngModel)]="editUserData.primaryRole">
                <option value="ROLE_USER">Utilisateur</option>
                <option value="ROLE_ADMIN">Administrateur</option>
              </select>
              <i class="bi bi-chevron-down selector-arrow"></i>
            </div>
          </div>
        </div>

        <div class="delete-modal-actions">
          <button type="button" class="modal-btn cancel" (click)="cancelEditDialog()" [disabled]="isSaving">
            Annuler
          </button>
          <button type="button" class="modal-btn save" (click)="confirmSaveUser()" [disabled]="isSaving">
            <span *ngIf="!isSaving"><i class="bi bi-check-lg me-2"></i>Enregistrer</span>
            <span *ngIf="isSaving"><i class="bi bi-arrow-repeat spin me-2"></i>Enregistrement...</span>
          </button>
        </div>
      </section>
    </section>
  `,
  styles: [`
    :host {
      display: block;
      width: calc(100% / 0.85);
      transform: scale(0.85);
      transform-origin: top left;
    }

    .users-inbox {
      overflow: hidden;
      min-height: 680px;
      border: 1px solid #dfe5ee;
      border-radius: 22px;
      background: #ffffff;
      color: #111827;
      box-shadow: 0 18px 45px rgba(15, 23, 42, 0.04);
    }

    .inbox-toolbar,
    .toolbar-actions,
    .select-action,
    .search-field,
    .inbox-row,
    .inbox-footer,
    .pager-actions {
      display: flex;
      align-items: center;
    }

    .inbox-toolbar {
      justify-content: space-between;
      gap: 24px;
      padding: 20px;
      border-bottom: 1px solid #dfe5ee;
      background: #ffffff;
    }

    .toolbar-actions {
      gap: 10px;
    }

    .select-action,
    .icon-action,
    .pager-btn {
      border: 1px solid #d7dee9;
      border-radius: 10px;
      background: #ffffff;
      color: #667085;
      box-shadow: 0 1px 2px rgba(15, 23, 42, 0.02);
    }

    .select-action {
      height: 52px;
      gap: 14px;
      padding: 0 16px;
      cursor: pointer;
    }

    .icon-action,
    .pager-btn {
      display: inline-grid;
      width: 52px;
      height: 52px;
      place-items: center;
      font-size: 22px;
      }

    .icon-action.danger {
      color: #b42318;
      background: linear-gradient(180deg, #fff5f5 0%, #fff 100%);
    }

    .icon-action.danger:hover:not(:disabled) {
      border-color: #fda29b;
      background: linear-gradient(180deg, #ffe8e6 0%, #fff5f5 100%);
      color: #7a271a;
    }

    .delete-modal-backdrop {
      position: fixed;
      inset: 0;
      z-index: 1200;
      background: rgba(15, 23, 42, 0.46);
      backdrop-filter: blur(6px);
    }

    .delete-modal {
      position: fixed;
      top: 50%;
      left: 50%;
      z-index: 1201;
      width: min(92vw, 520px);
      transform: translate(-50%, -50%);
      border-radius: 24px;
      border: 1px solid rgba(239, 68, 68, 0.14);
      background: linear-gradient(180deg, #ffffff 0%, #fffafa 100%);
      box-shadow: 0 28px 80px rgba(15, 23, 42, 0.28);
      padding: 1.4rem;
      color: #111827;
      animation: modalPop 180ms ease-out;
    }

    .delete-modal-header {
      display: flex;
      gap: 1rem;
      align-items: flex-start;
    }

    .delete-modal-icon {
      flex: 0 0 auto;
      width: 56px;
      height: 56px;
      border-radius: 18px;
      display: grid;
      place-items: center;
      background: linear-gradient(135deg, rgba(239, 68, 68, 0.14), rgba(248, 113, 113, 0.22));
      color: #b42318;
      font-size: 1.35rem;
      box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.6);
    }

    .delete-modal h3 {
      margin: 0 0 0.35rem;
      font-size: 1.2rem;
      font-weight: 700;
      letter-spacing: -0.02em;
      color: #1f2937;
    }

    .delete-modal p {
      margin: 0;
      color: #6b7280;
      line-height: 1.6;
      font-size: 0.97rem;
    }

    .delete-modal-body {
      margin-top: 1rem;
      padding: 1rem;
      border-radius: 18px;
      background: #f9fafb;
      border: 1px solid #eef2f7;
    }

    .warning-chip {
      display: inline-flex;
      align-items: center;
      gap: 0.45rem;
      padding: 0.45rem 0.75rem;
      margin-bottom: 0.85rem;
      border-radius: 999px;
      background: #fff1f2;
      color: #b42318;
      font-size: 0.84rem;
      font-weight: 600;
    }

    .delete-modal-actions {
      display: flex;
      gap: 0.75rem;
      justify-content: flex-end;
      margin-top: 1.2rem;
    }

    .modal-btn {
      min-width: 132px;
      height: 46px;
      border: 0;
      border-radius: 14px;
      font-weight: 700;
      font-size: 0.95rem;
      transition: transform 0.2s ease, box-shadow 0.2s ease, background 0.2s ease;
    }

    .modal-btn.cancel {
      background: #eef2ff;
      color: #344054;
    }

    .modal-btn.cancel:hover:not(:disabled) {
      background: #e0e7ff;
      transform: translateY(-1px);
    }

    .modal-btn.danger {
      background: linear-gradient(135deg, #ef4444 0%, #dc2626 100%);
      color: #fff;
      box-shadow: 0 14px 28px rgba(239, 68, 68, 0.25);
    }

    .modal-btn.danger:hover:not(:disabled) {
      transform: translateY(-1px);
      box-shadow: 0 18px 34px rgba(239, 68, 68, 0.32);
    }

    .modal-btn:disabled {
      opacity: 0.65;
      cursor: not-allowed;
    }

    .spin {
      animation: spin 0.8s linear infinite;
    }

    .modal-btn.save {
      background: linear-gradient(135deg, #465fff 0%, #2940d3 100%);
      color: #fff;
      box-shadow: 0 14px 28px rgba(70, 95, 255, 0.25);
    }

    .modal-btn.save:hover:not(:disabled) {
      transform: translateY(-1px);
      box-shadow: 0 18px 34px rgba(70, 95, 255, 0.32);
    }

    .edit-body {
      background: #fdfdff;
    }

    .form-label {
      display: block;
      margin-bottom: 6px;
      font-size: 0.85rem;
      font-weight: 600;
      color: #475467;
    }

    .admin-input {
      width: 100%;
      height: 44px;
      padding: 0 14px;
      border: 1px solid #d0d5dd;
      border-radius: 10px;
      background: #fff;
      color: #101828;
      font-size: 0.95rem;
      transition: all 0.2s ease;

      &:focus {
        outline: none;
        border-color: #465fff;
        box-shadow: 0 0 0 4px rgba(70, 95, 255, 0.1);
      }
    }

    .edit-icon {
      background: linear-gradient(135deg, rgba(70, 95, 255, 0.14), rgba(70, 95, 255, 0.22));
      color: #2940d3;
    }

    .role-selector-wrapper {
      position: relative;
      display: flex;
      align-items: center;
    }

    .role-select {
      appearance: none;
      cursor: pointer;
      padding-right: 40px !important;
    }

    .selector-arrow {
      position: absolute;
      right: 14px;
      pointer-events: none;
      color: #667085;
      font-size: 16px;
    }

    @keyframes modalPop {
      from {
        opacity: 0;
        transform: translate(-50%, -48%) scale(0.98);
      }
      to {
        opacity: 1;
        transform: translate(-50%, -50%) scale(1);
      }
    }

    @keyframes spin {
      from { transform: rotate(0deg); }
      to { transform: rotate(360deg); }
    }

    .icon-action:disabled,
    .pager-btn:disabled,
    .select-action:has(input:disabled) {
      cursor: not-allowed;
      opacity: 0.55;
    }

    .search-field {
      width: min(100%, 296px);
      height: 52px;
      gap: 12px;
      padding: 0 18px;
      border: 1px solid #cbd5e1;
      border-radius: 10px;
      background: #ffffff;
      color: #53627c;
      box-shadow: 0 1px 3px rgba(15, 23, 42, 0.08);
    }

    .search-field i {
      font-size: 24px;
    }

    .search-field input {
      width: 100%;
      border: 0;
      outline: 0;
      background: transparent;
      color: #344054;
      font-size: 16px;
    }

    .search-field input::placeholder {
      color: #8b93aa;
    }

    .inbox-list {
      overflow-x: auto;
    }

    .inbox-row {
      display: grid;
      grid-template-columns: 24px 24px minmax(120px, 150px) minmax(320px, 1fr) minmax(90px, auto) 92px;
      gap: 18px;
      min-height: 67px;
      padding: 0 20px;
      border-bottom: 1px solid #dfe5ee;
      color: #41506b;
      font-size: 16px;
      transition: background-color 160ms ease;
    }

    .inbox-row:hover,
    .inbox-row.selected {
      background: #f4f7fb;
    }

    .custom-check {
      position: relative;
      display: inline-grid;
      width: 20px;
      height: 20px;
      place-items: center;
    }

    .custom-check input {
      position: absolute;
      inset: 0;
      z-index: 1;
      margin: 0;
      cursor: pointer;
      opacity: 0;
    }

    .custom-check span {
      width: 18px;
      height: 18px;
      border: 1px solid #cbd5e1;
      border-radius: 5px;
      background: #ffffff;
    }

    .custom-check input:checked + span {
      border-color: #465fff;
      background: #465fff;
      box-shadow: inset 0 0 0 4px #ffffff;
    }

    .star-button {
      display: inline-grid;
      width: 24px;
      height: 24px;
      place-items: center;
      border: 0;
      background: transparent;
      color: #98a5ba;
      font-size: 21px;
      line-height: 1;
    }

    .sender {
      overflow: hidden;
      color: #17233d;
      font-size: 16px;
      font-weight: 500;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .message-preview {
      overflow: hidden;
      color: #465777;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .role-badge {
      justify-self: end;
      padding: 6px 12px;
      border-radius: 999px;
      background: #eafaf2;
      color: #039855;
      font-size: 14px;
      font-weight: 600;
      line-height: 1;
      white-space: nowrap;
    }

    .role-badge.admin {
      background: #fff1ed;
      color: #d92d20;
    }

    .role-badge.empty {
      background: #f2f4f7;
      color: #667085;
    }

    .date-label {
      justify-self: end;
      color: #8790a8;
      font-size: 14px;
      white-space: nowrap;
    }

    .state {
      padding: 80px 24px;
      color: #667085;
      text-align: center;
      font-size: 16px;
    }

    .state.error {
      color: #d92d20;
    }

    .inbox-footer {
      justify-content: center;
      gap: 18px;
      min-height: 72px;
      padding: 0 20px 14px;
      color: #465777;
      font-size: 16px;
    }

    .footer-count {
      font-weight: 500;
    }

    .pager-actions {
      gap: 10px;
    }

    .pager-actions.centered {
      justify-content: center;
    }

    .pager-btn {
      width: 42px;
      height: 42px;
      font-size: 22px;
    }

    :host-context(.dark) .users-inbox,
    :host-context(.dark) .inbox-toolbar,
    :host-context(.dark) .select-action,
    :host-context(.dark) .icon-action,
    :host-context(.dark) .pager-btn,
    :host-context(.dark) .search-field {
      border-color: #1e293b;
      background: #111827;
    }

    :host-context(.dark) .inbox-toolbar,
    :host-context(.dark) .inbox-row {
      border-color: #1e293b;
    }

    :host-context(.dark) .inbox-row:hover,
    :host-context(.dark) .inbox-row.selected {
      background: #172033;
    }

    :host-context(.dark) .sender,
    :host-context(.dark) .search-field input {
      color: #f8fafc;
    }

    :host-context(.dark) .users-inbox,
    :host-context(.dark) .message-preview,
    :host-context(.dark) .inbox-footer,
    :host-context(.dark) .date-label,
    :host-context(.dark) .select-action,
    :host-context(.dark) .icon-action,
    :host-context(.dark) .pager-btn,
    :host-context(.dark) .search-field,
    :host-context(.dark) .state {
      color: #94a3b8;
    }

    @media (max-width: 900px) {
      .inbox-toolbar {
        align-items: stretch;
        flex-direction: column;
      }

      .toolbar-actions {
        flex-wrap: wrap;
      }

      .search-field {
        width: auto;
      }

      .inbox-row {
        width: 920px;
      }
    }
  `]
})
export class ProfilePageComponent implements OnInit {
  users: PlatformUser[] = [];
  selectedUserIds = new Set<string>();
  searchTerm = '';
  currentPage = 1;
  showDeleteDialog = false;
  showEditDialog = false;
  isDeleting = false;
  isSaving = false;
  readonly pageSize = 8;
  isLoading = false;
  errorMessage = '';

  editingUser: PlatformUser | null = null;
  editUserData: any = {
    nom: '',
    email: '',
    primaryRole: 'ROLE_USER'
  };

  constructor(private userService: UserService) {}

  ngOnInit(): void {
    this.loadUsers();
  }

  get filteredUsers(): PlatformUser[] {
    const term = this.searchTerm.trim().toLowerCase();

    if (!term) {
      return this.users;
    }

    return this.users.filter(user =>
      this.normalize(user.nom).includes(term) ||
      this.normalize(user.email).includes(term) ||
      (user.roles || []).some(role => this.normalize(role).includes(term))
    );
  }

  get pagedUsers(): PlatformUser[] {
    const start = (this.currentPage - 1) * this.pageSize;
    return this.filteredUsers.slice(start, start + this.pageSize);
  }

  get totalPages(): number {
    return Math.max(1, Math.ceil(this.filteredUsers.length / this.pageSize));
  }

  get allVisibleSelected(): boolean {
    return this.pagedUsers.length > 0 && this.pagedUsers.every(user => this.selectedUserIds.has(user.id));
  }

  get showingLabel(): string {
    const total = this.filteredUsers.length;

    if (total === 0) {
       return 'Affichage de 0 à 0 sur 0';
    }

    const start = (this.currentPage - 1) * this.pageSize + 1;
    const end = Math.min(this.currentPage * this.pageSize, total);
       return `Affichage de ${start} à ${end} sur ${total}`;
  }

  loadUsers(): void {
    this.isLoading = true;
    this.errorMessage = '';

    this.userService.getUsers().subscribe({
      next: users => {
        this.users = users;
        this.currentPage = 1;
        this.selectedUserIds.clear();
        this.isLoading = false;
      },
      error: () => {
        this.errorMessage = 'Impossible de charger la liste des utilisateurs.';
        this.isLoading = false;
      }
    });
  }

  onSearchChange(term: string): void {
    this.searchTerm = term;
    this.currentPage = 1;
  }

  toggleVisibleUsers(event: Event): void {
    const checked = (event.target as HTMLInputElement).checked;

    this.pagedUsers.forEach(user => {
      if (checked) {
        this.selectedUserIds.add(user.id);
      } else {
        this.selectedUserIds.delete(user.id);
      }
    });
  }

  toggleUserSelection(userId: string, event: Event): void {
    const checked = (event.target as HTMLInputElement).checked;

    if (checked) {
      this.selectedUserIds.add(userId);
      return;
    }
    this.selectedUserIds.delete(userId);
  }

  openDeleteDialog(): void {
    if (this.selectedUserIds.size === 0 || this.isLoading || this.isDeleting) {
      return;
    }

    this.showDeleteDialog = true;
  }

  cancelDeleteDialog(): void {
    if (this.isDeleting) {
      return;
    }

    this.showDeleteDialog = false;
  }

  confirmDeleteSelectedUsers(): void {
    if (this.selectedUserIds.size === 0 || this.isLoading || this.isDeleting) {
      return;
    }

    const selectedIds = Array.from(this.selectedUserIds);
    this.isDeleting = true;
    this.errorMessage = '';

    forkJoin(selectedIds.map(userId => this.userService.deleteUser(userId))).subscribe({
      next: () => {
        this.showDeleteDialog = false;
        this.isDeleting = false;
        this.selectedUserIds.clear();
        this.loadUsers();
      },
      error: () => {
        this.errorMessage = 'Impossible de supprimer les utilisateurs sélectionnés.';
        this.isDeleting = false;
        this.showDeleteDialog = false;
      }
    });
  }

  openEditDialog(): void {
    if (this.selectedUserIds.size !== 1 || this.isLoading) {
      return;
    }

    const userId = Array.from(this.selectedUserIds)[0];
    this.editingUser = this.users.find(u => u.id === userId) || null;

    if (this.editingUser) {
      this.editUserData = {
        nom: this.editingUser.nom,
        email: this.editingUser.email,
        primaryRole: (this.editingUser.roles || []).includes('ROLE_ADMIN') ? 'ROLE_ADMIN' : 'ROLE_USER'
      };
      this.showEditDialog = true;
    }
  }

  cancelEditDialog(): void {
    if (this.isSaving) {
      return;
    }
    this.showEditDialog = false;
    this.editingUser = null;
  }

  confirmSaveUser(): void {
    if (!this.editingUser || this.isSaving) {
      return;
    }

    this.isSaving = true;
    const updatePayload = {
      nom: this.editUserData.nom,
      email: this.editUserData.email,
      roles: [this.editUserData.primaryRole]
    };

    this.userService.updateUser(this.editingUser.id, updatePayload).subscribe({
      next: () => {
        this.isSaving = false;
        this.showEditDialog = false;
        this.editingUser = null;
        this.loadUsers();
      },
      error: () => {
        this.errorMessage = "Erreur lors de la mise à jour de l'utilisateur.";
        this.isSaving = false;
      }
    });
  }

  isSelected(userId: string): boolean {
    return this.selectedUserIds.has(userId);
  }

  previousPage(): void {
    this.currentPage = Math.max(1, this.currentPage - 1);
  }

  nextPage(): void {
    this.currentPage = Math.min(this.totalPages, this.currentPage + 1);
  }

  getPrimaryRole(user: PlatformUser): string {
    const role = user.roles?.[0];

    if (!role) {
      return '';
    }
    return role.replace('ROLE_', '').toLowerCase().replace(/^./, char => char.toUpperCase());
  }

  isAdmin(user: PlatformUser): boolean {
    return (user.roles || []).some(role => role.toUpperCase().includes('ADMIN'));
  }

  formatDate(timestamp: number): string {
    if (!timestamp) {
      return '-';
    }

    const date = new Date(timestamp);
    const today = new Date();
    const sameDay = date.toDateString() === today.toDateString();

    if (sameDay) {
      return new Intl.DateTimeFormat('en-US', {
        hour: 'numeric',
        minute: '2-digit',
        hour12: true
      }).format(date).toLowerCase();
    }

    return new Intl.DateTimeFormat('en-US', {
      month: 'short',
      day: '2-digit'
    }).format(date);
  }

  private normalize(value: string | undefined | null): string {
    return (value || '').toLowerCase();
  }
}
