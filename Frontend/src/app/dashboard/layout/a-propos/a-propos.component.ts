import { Component } from '@angular/core';

interface StatItem {
  value: string;
  label: string;
}

interface ValuePillar {
  title: string;
  description: string;
  icon: string;
}

interface TimelineItem {
  year: string;
  title: string;
  description: string;
}

interface TestimonialItem {
  name: string;
  role: string;
  quote: string;
}

interface FaqItem {
  question: string;
  answer: string;
  open?: boolean;
}

@Component({
  selector: 'app-a-propos',
  templateUrl: './a-propos.component.html',
  styleUrls: ['./a-propos.component.scss']
})
export class AProposComponent {
  stats: StatItem[] = [
    { value: '500+', label: 'Tests generes par jour' },
    { value: '98%', label: 'Taux de couverture' },
    { value: '3x', label: 'Plus rapide qu avant' }
  ];

  values: ValuePillar[] = [
    {
      title: 'Performance',
      description: 'Tests generes en quelques secondes pour reduire le time-to-quality.',
      icon: 'bi-lightning-charge-fill'
    },
    {
      title: 'Fiabilite',
      description: 'Scenarios maintenables, execution stable et resultats exploitables.',
      icon: 'bi-shield-check'
    },
    {
      title: 'Intelligence',
      description: 'L IA comprend vos specs et propose des scripts adaptes au contexte.',
      icon: 'bi-cpu-fill'
    },
    {
      title: 'Collaboration',
      description: 'Concu pour les equipes QA, Dev et Produit autour d un flux unique.',
      icon: 'bi-people-fill'
    }
  ];

  timeline: TimelineItem[] = [
    {
      year: '2023',
      title: 'Naissance de Test2i',
      description: 'Prototype interne pour automatiser les tests repetitifs de regression.'
    },
    {
      year: '2024',
      title: 'Moteur IA integre',
      description: 'Generation de scenarios Gherkin et scripts Selenium basee sur vos besoins.'
    },
    {
      year: '2025',
      title: 'Execution intelligente',
      description: 'Rapports enrichis, screenshots et suivi des performances en un clic.'
    },
    {
      year: '2026',
      title: 'Plateforme collaborative',
      description: 'Connexion Jira/Xray et workflow complet de la story au resultat de test.'
    }
  ];

  testimonials: TestimonialItem[] = [
    {
      name: 'Nadia K.',
      role: 'QA Lead',
      quote: 'Test2i a reduit notre temps de creation de tests de plusieurs jours a quelques heures.'
    },
    {
      name: 'Mohamed B.',
      role: 'Product Manager',
      quote: 'Nous validons les user stories plus vite, avec des scenarios plus clairs pour toute l equipe.'
    },
    {
      name: 'Aymen S.',
      role: 'Tech Lead',
      quote: 'La generation IA + les rapports detailles ont vraiment fiabilise nos livraisons.'
    }
  ];

  faqs: FaqItem[] = [
    {
      question: 'Test2i remplace-t-il les testeurs QA ?',
      answer: 'Non. Test2i accelere et assiste le travail QA. Les testeurs gardent le pilotage et la validation metier.',
      open: true
    },
    {
      question: 'Peut-on tester une application web sans code source ?',
      answer: 'Oui. Vous pouvez fournir une URL d application et Test2i genere des scenarios bases sur les elements detectes.'
    },
    {
      question: 'Les scripts sont-ils exportables vers Jira/Xray ?',
      answer: 'Oui. La plateforme supporte l integration Jira/Xray pour tracer vos tests et executions.'
    },
    {
      question: 'Combien de temps pour lancer une premiere execution ?',
      answer: 'Quelques minutes: creation du projet, generation de tests, puis execution et rapport PDF.'
    }
  ];

  toggleFaq(index: number): void {
    this.faqs = this.faqs.map((faq, i) => ({
      ...faq,
      open: i === index ? !faq.open : faq.open
    }));
  }
}
