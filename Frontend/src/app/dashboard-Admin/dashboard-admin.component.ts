import { CommonModule } from '@angular/common';
import { Component, OnInit } from '@angular/core';
import { NavigationEnd, Router, RouterModule } from '@angular/router';
import { filter, map } from 'rxjs/operators';

type MetricCard = {
  label: string;
  value: string;
  change: string;
  trend: 'up' | 'down';
  icon: string;
};

type MonthlySale = {
  month: string;
  value: number;
};

@Component({
  selector: 'app-dashboard-admin',
  standalone: true,
  imports: [CommonModule, RouterModule],
  templateUrl: './dashboard-admin.component.html',
  styleUrls: ['./dashboard-admin.component.scss']
})
export class DashboardAdminComponent implements OnInit {
  isDarkMode = false;
  isDefaultDashboard = true;

  metrics: MetricCard[] = [
    { label: 'Customers', value: '3,782', change: '11.01%', trend: 'up', icon: 'bi-people' },
    { label: 'Orders', value: '5,359', change: '9.05%', trend: 'down', icon: 'bi-box-seam' }
  ];

  monthlySales: MonthlySale[] = [
    { month: 'Jan', value: 39 },
    { month: 'Feb', value: 94 },
    { month: 'Mar', value: 47 },
    { month: 'Apr', value: 72 },
    { month: 'May', value: 44 },
    { month: 'Jun', value: 46 },
    { month: 'Jul', value: 70 },
    { month: 'Aug', value: 25 },
    { month: 'Sep', value: 51 },
    { month: 'Oct', value: 96 },
    { month: 'Nov', value: 68 },
    { month: 'Dec', value: 26 }
  ];

  statisticsBars = [44, 68, 58, 84, 62, 91, 74, 52, 87, 63, 77, 69];

  constructor(private router: Router) {}

  ngOnInit(): void {
    const savedTheme = localStorage.getItem('theme');
    this.isDarkMode = savedTheme
      ? savedTheme === 'dark'
      : document.documentElement.classList.contains('dark');

    this.applyTheme(this.isDarkMode);

    this.router.events
      .pipe(
        filter(event => event instanceof NavigationEnd),
        map((event: any) => event.urlAfterRedirects || event.url)
      )
      .subscribe((url: string) => {
        this.isDefaultDashboard = url === '/dashboard-admin' || url === '/dashboard-admin/';
      });

    this.isDefaultDashboard = this.router.url === '/dashboard-admin' || this.router.url === '/dashboard-admin/';
  }

  toggleDarkMode(): void {
    this.isDarkMode = !this.isDarkMode;
    this.applyTheme(this.isDarkMode);
    localStorage.setItem('theme', this.isDarkMode ? 'dark' : 'light');
  }

  private applyTheme(useDark: boolean): void {
    document.documentElement.classList.toggle('dark', useDark);
  }
}
