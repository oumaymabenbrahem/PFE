import { NgModule } from '@angular/core';
import { BrowserModule } from '@angular/platform-browser';
import { CommonModule } from '@angular/common';
import { ReactiveFormsModule, FormsModule } from '@angular/forms';

import { AppRoutingModule } from './app-routing.module';
import { AppComponent } from './app.component';
import { HeaderComponent } from './dashboard/layout/header/header.component';
import { FooterComponent } from './dashboard/layout/footer/footer.component';
import { HomeComponent } from './dashboard/pages/home/home.component';
import { SignInComponent } from './dashboard/pages/sign-in/sign-in.component';
import { SignUpComponent } from './dashboard/pages/sign-up/sign-up.component';
import { UploadprojetComponent } from './dashboard/pages/uploadprojet/uploadprojet.component';
import { ProjectsListComponent } from './dashboard/pages/projects-list/projects-list.component';
import { CoreModule } from './core/core.module';
import { TestScriptsComponent } from './dashboard/pages/test-scripts/test-scripts.component';
import { ChatbotComponent } from './shared/components/chatbot/chatbot.component';
import { AProposComponent } from './dashboard/layout/a-propos/a-propos.component';
import { ForgotPasswordComponent } from './dashboard/pages/forgot-password/forgot-password.component';
import { ForgotPasswordVerificationComponent } from './dashboard/pages/forgot-password-verification/forgot-password-verification.component';
import { ResetPasswordComponent } from './dashboard/pages/reset-password/reset-password.component';

@NgModule({
  declarations: [
    AppComponent,
    HeaderComponent,
    FooterComponent,
    HomeComponent,
    SignInComponent,
    SignUpComponent,
    UploadprojetComponent,
    ProjectsListComponent,
    TestScriptsComponent,
    ChatbotComponent,
    AProposComponent,
    ForgotPasswordComponent,
    ForgotPasswordVerificationComponent,
    ResetPasswordComponent
  ],
  imports: [
    BrowserModule,
    CommonModule,
    FormsModule,
    ReactiveFormsModule,
    AppRoutingModule,
    CoreModule
  ],
  providers: [],
  bootstrap: [AppComponent]
})
export class AppModule { }
