import { Component } from '@angular/core';

@Component({
  selector: 'app-chatbot',
  templateUrl: './chatbot.component.html',
  styleUrls: ['./chatbot.component.scss']
})
export class ChatbotComponent {
  isChatOpen: boolean = false;
  messages: { text: string; sender: 'user' | 'bot' }[] = [
    { text: 'Bonjour ! Comment puis-je vous aider aujourd\'hui ?', sender: 'bot' }
  ];
  userInput: string = '';

  toggleChat(): void {
    this.isChatOpen = !this.isChatOpen;
  }

  sendMessage(): void {
    if (this.userInput.trim() === '') return;

    // Add user message
    this.messages.push({ text: this.userInput, sender: 'user' });
    
    const userMessage = this.userInput;
    this.userInput = '';

    // Simulate bot response
    setTimeout(() => {
      this.messages.push({
        text: 'Ceci est une réponse automatique de votre assistant.',
        sender: 'bot'
      });
    }, 1000);
  }
}
