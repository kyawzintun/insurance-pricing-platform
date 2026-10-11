import { InjectionToken } from '@angular/core';
import { environment } from '@environments/environment';

export const GATEWAY_URL = new InjectionToken<string>('Gateway base URL', {
  providedIn: 'root',
  factory: () => environment.apiBaseUrl.replace(/\/+$/, ''),
});
