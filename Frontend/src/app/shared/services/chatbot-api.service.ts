import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, Subject } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface ChatMessage {
  id?: string;
  userMessage: string;
  botResponse: string;
  messageType: string;
  contextData?: any;
  createdAt?: string;
}

export interface ChatRequest {
  userMessage: string;
  includeContext: boolean;
}

@Injectable({
  providedIn: 'root'
})
export class ChatbotApiService {

  private apiUrl = environment.apiUrl || 'http://localhost:8081/api';
  private messageSubject = new Subject<ChatMessage>();
  public message$ = this.messageSubject.asObservable();

  constructor(private http: HttpClient) { }

  /**
   * Send message to chatbot and get response
   */
  sendMessage(message: string, includeContext: boolean = true): Observable<ChatMessage> {
    const request: ChatRequest = {
      userMessage: message,
      includeContext: includeContext
    };

    return this.http.post<ChatMessage>(`${this.apiUrl}/chatbot/message`, request);
  }

  /**
   * Get conversation history
   */
  getHistory(limit: number = 0): Observable<any> {
    let url = `${this.apiUrl}/chatbot/history`;
    if (limit > 0) {
      url += `?limit=${limit}`;
    }
    return this.http.get(url);
  }

  /**
   * Delete specific message
   */
  deleteMessage(messageId: string): Observable<any> {
    return this.http.delete(`${this.apiUrl}/chatbot/message/${messageId}`);
  }

  /**
   * Clear entire conversation
   */
  clearHistory(): Observable<any> {
    return this.http.delete(`${this.apiUrl}/chatbot/history`);
  }

  /**
   * Emit message for component subscription
   */
  emitMessage(message: ChatMessage) {
    this.messageSubject.next(message);
  }
}
