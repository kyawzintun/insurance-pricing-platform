import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { provideState, provideStore } from '@ngrx/store';
import { provideEffects } from '@ngrx/effects';

import { routes } from '@app/app.routes';
import { authFeature } from '@app/store/auth/auth.reducer';
import { AuthEffects } from '@app/store/auth/auth.effects';
import { authInterceptor } from '@app/core/interceptors/auth.interceptor';
import { quoteFeature } from '@app/store/quotes/quote.reducer';
import { QuoteEffects } from '@app/store/quotes/quote.effects';
import { AuditEffects, auditFeature } from '@app/store/audit/audit.store';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    provideHttpClient(withInterceptors([authInterceptor])),
    provideStore(),
    provideState(authFeature),
    provideState(quoteFeature),
    provideState(auditFeature),
    provideEffects(AuthEffects, QuoteEffects, AuditEffects),
  ],
};
