import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';
import { HomeComponent } from './dashboard/pages/home/home.component';
import { SignInComponent } from './dashboard/pages/sign-in/sign-in.component';
import { SignUpComponent } from './dashboard/pages/sign-up/sign-up.component';
import { UploadprojetComponent } from './dashboard/pages/uploadprojet/uploadprojet.component';
import { ProjectsListComponent } from './dashboard/pages/projects-list/projects-list.component';
import { AProposComponent } from './dashboard/layout/a-propos/a-propos.component';
import { TestScriptsComponent } from './dashboard/pages/test-scripts/test-scripts.component';
import { ForgotPasswordComponent } from './dashboard/pages/forgot-password/forgot-password.component';
import { ForgotPasswordVerificationComponent } from './dashboard/pages/forgot-password-verification/forgot-password-verification.component';
import { ResetPasswordComponent } from './dashboard/pages/reset-password/reset-password.component';
import { AuthGuard } from './core/guards/auth.guard';

const routes: Routes = [
  // Routes publiques
  { path: '', component: HomeComponent },
  { path: 'a-propos', component: AProposComponent },
  { path: 'scripts', component: TestScriptsComponent },
  { path: 'login', component: SignInComponent },
  { path: 'forgot-password', component: ForgotPasswordComponent },
  { path: 'forgot-password/verification', component: ForgotPasswordVerificationComponent },
  { path: 'reset-password', component: ResetPasswordComponent },
  { path: 'signup', component: SignUpComponent },
  { path: 'register', component: SignUpComponent },

  // Routes protégées (nécessitent la connexion)
  { 
    path: 'dashboard', 
    component: HomeComponent,
    canActivate: [AuthGuard]
  },
  { 
    path: 'upload-projet', 
    component: UploadprojetComponent,
    canActivate: [AuthGuard]
  },
  { 
    path: 'projects', 
    component: ProjectsListComponent,
    canActivate: [AuthGuard]
  }
];

@NgModule({
  imports: [RouterModule.forRoot(routes)],
  exports: [RouterModule]
})
export class AppRoutingModule { }
