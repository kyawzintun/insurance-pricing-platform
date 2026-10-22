import { inject, Injectable } from '@angular/core';
import { Store } from '@ngrx/store';
import {
  catchError,
  finalize,
  map,
  Observable,
  of,
  shareReplay,
  switchMap,
  tap,
  throwError,
  timeout,
} from 'rxjs';
import { AuthApiService } from './auth-api.service';
import { decodeSession } from './decode-session';
import { AuthSession } from './auth.models';
import { AuthActions } from '@app/store/auth/auth.actions';

@Injectable({ providedIn: 'root' })
export class SessionService {
  private readonly api = inject(AuthApiService);
  private readonly store = inject(Store);
  private pending?: Observable<AuthSession>;
  private generation = 0;

  refresh(): Observable<AuthSession> {
    if (!this.pending) {
      const generation = this.generation;
      this.pending = this.api.refresh().pipe(
        timeout(10000),
        map((value) => {
          const session = decodeSession(value.accessToken);
          if (!session || generation !== this.generation) throw new Error('Invalid session');
          return session;
        }),
        tap((session) => this.store.dispatch(AuthActions.sessionRestored({ session }))),
        catchError((error) => {
          this.store.dispatch(AuthActions.loginFailure());
          return throwError(() => error);
        }),
        finalize(() => (this.pending = undefined)),
        shareReplay({ bufferSize: 1, refCount: false }),
      );
    }
    return this.pending;
  }
  restore() {
    return this.refresh().pipe(catchError(() => of(null)));
  }
  logout() {
    this.generation++;
    this.store.dispatch(AuthActions.logout());
    // Wait for any rotating response to set its cookie before sending logout.
    const settled = this.pending ? this.pending.pipe(catchError(() => of(null))) : of(null);
    settled
      .pipe(
        switchMap(() => this.api.logout()),
        timeout(12000),
        catchError(() => of(null)),
      )
      .subscribe();
  }
}
