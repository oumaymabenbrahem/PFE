import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from 'src/environments/environment';

export interface DashboardStatistics {
  totalTests: number;
  totalProjects: number;
  successRate: number;
  monthlyActivity: { [key: string]: number };
  testTypeDistribution: { [key: string]: number };
  priorityDistribution: { [key: string]: number };
  statusDistribution: { [key: string]: number };
  totalTestsGrowth: number;
  totalProjectsGrowth: number;
  averageExecutionTime: number;
  totalUsers: number;
}

@Injectable({
  providedIn: 'root'
})
export class DashboardAdminService {
  private apiUrl = `${environment.apiUrl}/admin/statistics`;

  constructor(private http: HttpClient) {}

  getStatistics(): Observable<DashboardStatistics> {
    return this.http.get<DashboardStatistics>(this.apiUrl);
  }
}
