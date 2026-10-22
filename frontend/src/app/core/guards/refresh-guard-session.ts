import { inject } from '@angular/core';
import { Store } from '@ngrx/store';
import { map, Observable } from 'rxjs';
import { SessionService } from '@app/core/auth/session.service';
import { authFeature, AuthState, sessionIsActive } from '@app/store/auth/auth.reducer';

/** Startup initialization finishes before navigation; renew an expired in-memory session on later navigation. */
export function withGuardSession<T>(decide: (state: AuthState) => T): T | Observable<T> {
  const store = inject(Store);
  const read = store.selectSignal(authFeature.selectAuthState);
  const state = read();
  if (state.isAuthenticated && !sessionIsActive(state)) {
    return inject(SessionService)
      .restore()
      .pipe(map(() => decide(read())));
  }
  return decide(state);
}
