export interface LoginRequest {
  email: string;
  password: string;
}
export interface RegisterRequest extends LoginRequest {
  firstName: string;
  lastName: string;
}
export interface TokenResponse {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
}
export interface RegisteredUser {
  id: string;
  email: string;
  firstName: string;
  lastName: string;
  role: string;
  status: string;
  createdAt: string;
}
export type Role = 'CUSTOMER' | 'ADMIN';
export interface AuthSession {
  accessToken: string;
  userId: string;
  roles: Role[];
  expiresAt: number;
}
