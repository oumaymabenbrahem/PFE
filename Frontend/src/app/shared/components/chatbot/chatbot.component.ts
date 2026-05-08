import { Component, OnInit, ViewChild, ElementRef, AfterViewChecked } from '@angular/core';
import { ChatbotApiService, ChatMessage } from '../../services/chatbot-api.service';

interface DisplayMessage {
  id?: string;
  text: string;
  sender: 'user' | 'bot';
  timestamp?: string;
  messageType?: string;
  isLoading?: boolean;
}

@Component({
  selector: 'app-chatbot',
  templateUrl: './chatbot.component.html',
  styleUrls: ['./chatbot.component.scss']
})
export class ChatbotComponent implements OnInit, AfterViewChecked {
  isChatOpen: boolean = false;
  messages: DisplayMessage[] = [
    {
      text: 'Bonjour ! 👋 Je suis votre assistant IA. Je peux répondre à vos questions sur les tests d\'automatisation, Gherkin, vos projets et plus encore. Comment puis-je vous aider ?',
      sender: 'bot',
      timestamp: this.getCurrentTime()
    }
  ];
  userInput: string = '';
  isLoading: boolean = false;
  includeContext: boolean = true;
  showClearButton: boolean = false;

  @ViewChild('chatBody') private chatBody: ElementRef | undefined;
  private shouldScroll: boolean = false;

  constructor(private chatbotApiService: ChatbotApiService) {}

  ngOnInit(): void {
    this.loadConversationHistory();
  }

  ngAfterViewChecked(): void {
    if (this.shouldScroll) {
      this.scrollToBottom();
      this.shouldScroll = false;
    }
  }

  toggleChat(): void {
    this.isChatOpen = !this.isChatOpen;
    if (this.isChatOpen) {
      this.shouldScroll = true;
    }
  }

  sendMessage(): void {
    if (this.userInput.trim() === '') return;
    if (this.isLoading) return;

    // Add user message to display
    this.messages.push({
      text: this.userInput,
      sender: 'user',
      timestamp: this.getCurrentTime()
    });

    const userMessage = this.userInput;
    this.userInput = '';
    this.isLoading = true;
    this.shouldScroll = true;

    // Add loading indicator
    this.messages.push({
      text: 'En train de traiter...',
      sender: 'bot',
      isLoading: true
    });

    // Call backend API
    this.chatbotApiService.sendMessage(userMessage, this.includeContext).subscribe(
      (response: ChatMessage) => {
        // Remove loading indicator
        this.messages.pop();

        // Add bot response
        this.messages.push({
          id: response.id,
          text: response.botResponse,
          sender: 'bot',
          timestamp: this.getCurrentTime(),
          messageType: response.messageType
        });

        this.isLoading = false;
        this.shouldScroll = true;
      },
      (error) => {
        // Remove loading indicator
        this.messages.pop();

        // Add error message
        this.messages.push({
          text: 'Désolé, une erreur est survenue. Veuillez réessayer.',
          sender: 'bot',
          timestamp: this.getCurrentTime(),
          messageType: 'ERROR'
        });

        console.error('Error sending message:', error);
        this.isLoading = false;
        this.shouldScroll = true;
      }
    );
  }

  /**
   * Load conversation history
   */
  loadConversationHistory(): void {
    this.chatbotApiService.getHistory(20).subscribe(
      (response: any) => {
        if (response.messages && response.messages.length > 0) {
          // Clear welcome message
          this.messages = [];

          // Load history in reverse order (oldest first)
          const history = response.messages.reverse();
          history.forEach((msg: ChatMessage) => {
            // Add user message
            if (msg.userMessage) {
              this.messages.push({
                id: msg.id,
                text: msg.userMessage,
                sender: 'user',
                timestamp: msg.createdAt ? new Date(msg.createdAt).toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' }) : undefined
              });
            }

            // Add bot response
            if (msg.botResponse) {
              this.messages.push({
                id: msg.id,
                text: msg.botResponse,
                sender: 'bot',
                timestamp: msg.createdAt ? new Date(msg.createdAt).toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' }) : undefined,
                messageType: msg.messageType
              });
            }
          });

          this.showClearButton = this.messages.length > 2;
        }
      },
      (error) => {
        console.error('Error loading conversation history:', error);
      }
    );
  }

  /**
   * Clear entire conversation
   */
  clearConversation(): void {
    if (!confirm('Êtes-vous sûr de vouloir effacer tout l\'historique de conversation ?')) {
      return;
    }

    this.chatbotApiService.clearHistory().subscribe(
      () => {
        this.messages = [
          {
            text: 'Historique effacé. Comment puis-je vous aider ?',
            sender: 'bot',
            timestamp: this.getCurrentTime()
          }
        ];
        this.showClearButton = false;
      },
      (error) => {
        console.error('Error clearing conversation:', error);
        alert('Erreur lors de la suppression de l\'historique');
      }
    );
  }

  /**
   * Delete specific message
   */
  deleteMessage(messageId?: string): void {
    if (!messageId) return;

    this.chatbotApiService.deleteMessage(messageId).subscribe(
      () => {
        // Remove message from display
        this.messages = this.messages.filter(msg => msg.id !== messageId);
      },
      (error) => {
        console.error('Error deleting message:', error);
      }
    );
  }

  /**
   * Toggle context inclusion
   */
  toggleContext(): void {
    this.includeContext = !this.includeContext;
  }

  /**
   * Scroll to bottom of chat
   */
  private scrollToBottom(): void {
    if (this.chatBody) {
      setTimeout(() => {
        this.chatBody!.nativeElement.scrollTop = this.chatBody!.nativeElement.scrollHeight;
      }, 0);
    }
  }

  /**
   * Get current time in HH:MM format
   */
  private getCurrentTime(): string {
    return new Date().toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' });
  }

  /**
   * Copy message to clipboard
   */
  copyMessage(text: string): void {
    navigator.clipboard.writeText(text).then(() => {
      alert('Message copié');
    }).catch(err => {
      console.error('Error copying message:', err);
    });
  }
}
