import { CommonModule } from '@angular/common';
import { Component, OnInit } from '@angular/core';
import { NavigationEnd, Router, RouterModule } from '@angular/router';
import { filter, map } from 'rxjs/operators';
import { DashboardAdminService, DashboardStatistics } from '../core/services/dashboard-admin.service';

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
  count: number;
};

@Component({
  selector: 'app-dashboard-admin',
  standalone: true,
  imports: [CommonModule, RouterModule],
  providers: [DashboardAdminService],
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
    { month: 'Jan', value: 39, count: 0 },
    { month: 'Feb', value: 94, count: 0 },
    { month: 'Mar', value: 47, count: 0 },
    { month: 'Apr', value: 72, count: 0 },
    { month: 'May', value: 44, count: 0 },
    { month: 'Jun', value: 46, count: 0 },
    { month: 'Jul', value: 70, count: 0 },
    { month: 'Aug', value: 25, count: 0 },
    { month: 'Sep', value: 51, count: 0 },
    { month: 'Oct', value: 96, count: 0 },
    { month: 'Nov', value: 68, count: 0 },
    { month: 'Dec', value: 26, count: 0 }
  ];

  statisticsBars: { label: string, value: number }[] = [];
  topTypes: { label: string, count: number }[] = [];
  successFailureStats = { passed: 0, failed: 0, total: 0 };
  executionCoverage = 0;
  avgExecutionTime = 0;
  maxActivity = 10;
  maxDistribution = 10;
  selectedTab: 'type' | 'priority' | 'status' = 'type';
  fullStats?: DashboardStatistics;
  successRate = 0;
  totalUsers = 0;

  constructor(
    private router: Router,
    private dashboardService: DashboardAdminService
  ) {}

  ngOnInit(): void {
    this.loadStatistics();
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

  loadStatistics(): void {
    this.dashboardService.getStatistics().subscribe({
      next: (stats: DashboardStatistics) => {
        this.successRate = stats.successRate;
        this.totalUsers = stats.totalUsers;

        this.metrics = [
          {
            label: 'Tests Générés',
            value: stats.totalTests.toLocaleString(),
            change: '+12%',
            trend: 'up',
            icon: 'bi-file-earmark-code'
          },
          {
            label: 'Projets Actifs',
            value: stats.totalProjects.toLocaleString(),
            change: '+5%',
            trend: 'up',
            icon: 'bi-kanban'
          }
        ];

        this.fullStats = stats;
        
        // Calculate max and filter empty months
        const activityValues = Object.values(stats.monthlyActivity);
        this.maxActivity = Math.max(...activityValues, 10);

        this.monthlySales = Object.entries(stats.monthlyActivity)
          .map(([month, value]) => ({
            month,
            count: value,
            value: this.normalizeValue(value, stats.monthlyActivity)
          }))
          .filter(sale => sale.count > 0);

        this.calculateAdvancedStats(stats);
        
        this.avgExecutionTime = stats.averageExecutionTime || (stats as any)['average_execution_time'] || 0;
        
        // Update metrics with real growth
        this.metrics = [
          { 
            label: 'Tests Générés', 
            value: stats.totalTests.toLocaleString(), 
            icon: 'bi-file-earmark-code', 
            trend: stats.totalTestsGrowth >= 0 ? 'up' : 'down', 
            change: (stats.totalTestsGrowth >= 0 ? '+' : '-') + Math.abs(Math.round(stats.totalTestsGrowth)) + '%' 
          },
          { 
            label: 'Projets Actifs', 
            value: stats.totalProjects.toLocaleString(), 
            icon: 'bi-layout-sidebar-inset', 
            trend: stats.totalProjectsGrowth >= 0 ? 'up' : 'down', 
            change: (stats.totalProjectsGrowth >= 0 ? '+' : '-') + Math.abs(Math.round(stats.totalProjectsGrowth)) + '%' 
          }
        ];

        this.updateDistributionChart();
      },
      error: (err) => console.error('Error loading dashboard stats', err)
    });
  }

  switchTab(tab: 'type' | 'priority' | 'status'): void {
    this.selectedTab = tab;
    this.updateDistributionChart();
  }

  private updateDistributionChart(): void {
    if (!this.fullStats) return;

    let data: { [key: string]: number } = {};
    switch (this.selectedTab) {
      case 'type': data = this.fullStats.testTypeDistribution; break;
      case 'priority': data = this.fullStats.priorityDistribution; break;
      case 'status': data = this.fullStats.statusDistribution; break;
    }

    const dataValues = Object.values(data);
    this.maxDistribution = Math.max(...dataValues, 10);

    this.statisticsBars = Object.entries(data).map(([label, value]) => ({
      label,
      value: this.normalizeValue(value, data)
    }));
  }

  private calculateAdvancedStats(stats: DashboardStatistics): void {
    // Top Types
    this.topTypes = Object.entries(stats.testTypeDistribution)
      .map(([label, count]) => ({ label, count }))
      .sort((a, b) => b.count - a.count)
      .slice(0, 3);

    // Success vs Failure - Improved mapping
    const lookup = (keys: string[]) => keys.reduce((sum, key) => sum + (stats.statusDistribution[key] || 0), 0);
    
    const passed = lookup(['PASSED', 'SUCCESS', 'OK', 'COMPLETED']);
    const failed = lookup(['FAILED', 'ERROR', 'FAILURE', 'TIMEOUT', 'CRASHED']);
    const total = passed + failed;
    
    this.successFailureStats = {
      passed,
      failed,
      total: total
    };

    // Coverage/Time calculation
    const totalGenerated = stats.totalTests;
    this.executionCoverage = totalGenerated > 0 ? (total / totalGenerated) * 100 : 0;
  }

  private normalizeValue(value: number, allData: { [key: string]: number }): number {
    const max = Math.max(...Object.values(allData), 10);
    return (value / max) * 100;
  }

  calculateSpeedScore(avgTime: number): number {
    if (!avgTime || avgTime <= 0) return 0;
    // Assume 2 seconds is very fast (100%), and 20 seconds is slow (~20%)
    const score = 100 - (avgTime * 4);
    return Math.max(10, Math.min(100, score));
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
