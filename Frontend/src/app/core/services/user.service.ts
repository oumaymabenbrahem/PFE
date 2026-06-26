import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface PlatformUser {
  id: string;
  email: string;
  nom: string;
  roles: string[];
  createdAt: number;
}

@Injectable({
  providedIn: 'root'
})
export class UserService {
  private readonly apiUrl = `${environment.apiUrl}/users`;

  constructor(private http: HttpClient) {}

  getUsers(): Observable<PlatformUser[]> {
    return this.http.get<PlatformUser[]>(this.apiUrl);
  }

  deleteUser(userId: string): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/${userId}`);
  }

  updateUser(userId: string, userData: any): Observable<PlatformUser> {
    return this.http.put<PlatformUser>(`${this.apiUrl}/${userId}`, userData);
  }
}
