// Modèle User

export interface User {
  id: string | number;
  email: string;
  nom: string;
  roles: string[];
}

//DTO de connexion, Données envoyées lors de l'authentification

export interface LoginDto {
  email: string;
  password: string;
}

//DTO d'enregistrement, Données envoyées lors de la création d'un compte
 
export interface RegisterDto {
  email: string;
  nom: string;
  password: string;
  confirmPassword: string;
}

//Réponse d'authentification,Retournée par le serveur après login/register
 
export interface AuthResponse {
  token: string;
  user: User;
}

//Payload JWT décodé, Structure du contenu du token JWT
 
export interface JwtPayload {
  sub: string | number; // user id
  email: string;
  nom: string;
  roles: string[];
  iat: number;
  exp: number;
}
