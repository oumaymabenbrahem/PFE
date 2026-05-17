import { Component, OnInit } from '@angular/core';
import { Router } from '@angular/router';

@Component({
  selector: 'app-root',
  templateUrl: './app.component.html',
  styleUrls: ['./app.component.scss']
})
export class AppComponent implements OnInit {
  title = 'Frontend';

  constructor(private router: Router) {}

  ngOnInit(): void {
    const savedTheme = localStorage.getItem('theme');
    const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
    const shouldUseDark = savedTheme ? savedTheme === 'dark' : prefersDark;

    document.documentElement.classList.toggle('dark', shouldUseDark);
    if (!savedTheme) {
      localStorage.setItem('theme', shouldUseDark ? 'dark' : 'light');
    }
  }

  get isAdminDashboard(): boolean {
    return this.router.url.startsWith('/dashboard-admin');
  }
}
