import { HttpErrorResponse } from '@angular/common/http';
export function quoteErrorMessage(
  error: unknown,
  context: 'create' | 'detail' | 'history' | 'catalog',
): string {
  if (!(error instanceof HttpErrorResponse))
    return 'Unable to load quote information. Please try again.';
  if (error.status === 401) return 'Please sign in again.';
  if (error.status === 403) return 'This quote feature requires a customer account.';
  if (error.status === 404)
    return context === 'detail' ? 'Quote not found.' : 'Selected vehicle is no longer available.';
  if (error.status === 400) return 'Please check your quote information.';
  if (error.status === 502) return 'Unable to calculate the premium right now.';
  if (error.status === 503) return 'Pricing service is temporarily unavailable. Please try again.';
  return 'Unable to connect to the server. Please try again.';
}
