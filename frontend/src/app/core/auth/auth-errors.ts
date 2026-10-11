import { HttpErrorResponse } from '@angular/common/http';

export function authErrorMessage(error: unknown, operation: 'login' | 'register'): string {
  if (error instanceof HttpErrorResponse) {
    if (operation === 'login' && error.status === 401) return 'Invalid email or password.';
    if (operation === 'register' && error.status === 409)
      return 'This email is already registered.';
    if (error.status === 0 || error.status >= 500)
      return 'Unable to connect to the server. Please try again.';
    if (error.status === 400) return 'Please check your details and try again.';
  }
  return 'Unable to complete your request. Please try again.';
}
