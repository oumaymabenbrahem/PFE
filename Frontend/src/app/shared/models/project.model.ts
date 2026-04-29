export interface ProjectRequest {
  nom: string;
  description?: string;
  specificationType: 'CODE_FICHIER' | 'USER_STORY' | 'LIEN_GIT' | 'LIEN_APPLICATION';
  specificationContenu?: string;
  focusOptionnel?: string;
}

export interface ProjectResponse {
  id: string;
  nom: string;
  description: string;
  specificationType: 'CODE_FICHIER' | 'USER_STORY' | 'LIEN_GIT' | 'LIEN_APPLICATION';
  specificationContenu: string;
  focusOptionnel?: string;
  statut: string;
  ownerName: string;
  ownerEmail: string;
  nombreScripts: number;
  fichierGenere?: string;
  executionLogs?: string;
  createdAt: string;
  fichierNom?: string;
  fichierType?: string;
  fichierTaille?: number;
}
