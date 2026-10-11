import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { GATEWAY_URL } from '@app/core/auth/gateway.config';
import {
  LoginRequest,
  RegisterRequest,
  RegisteredUser,
  TokenResponse,
} from '@app/core/auth/auth.models';

@Injectable({ providedIn: 'root' })
export class AuthApiService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = inject(GATEWAY_URL);
  register(request: RegisterRequest) {
    return this.http.post<RegisteredUser>(`${this.baseUrl}/api/v1/auth/register`, request);
  }
  login(request: LoginRequest) {
    return this.http.post<TokenResponse>(`${this.baseUrl}/api/v1/auth/login`, request);
  }
}
