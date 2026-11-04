import { firstValueFrom } from 'rxjs';
import {
  ApplicationConfig,
  provideBrowserGlobalErrorListeners,
  provideAppInitializer,
  inject,
} from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter } from '@angular/router';

import { routes } from '@app/app.routes';
import { SessionService } from '@app/core/auth/session.service';
import { authInterceptor } from '@app/core/interceptors/auth.interceptor';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideAppInitializer(() => firstValueFrom(inject(SessionService).restore())),
    provideRouter(routes),
    provideHttpClient(withInterceptors([authInterceptor])),
  ],
};
