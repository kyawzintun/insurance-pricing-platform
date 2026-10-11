import { Component, computed, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Store } from '@ngrx/store';
import { finalize } from 'rxjs';
import { AuthApiService } from '@app/core/auth/auth-api.service';
import { authErrorMessage } from '@app/core/auth/auth-errors';
import { nonBlank, passwordBytes } from '@app/core/auth/auth.validators';
import { decodeSession } from '@app/core/auth/decode-session';
import { AuthActions } from '@app/store/auth/auth.actions';

@Component({
  selector: 'app-login',
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './login.component.html',
})
export class LoginComponent {
  private readonly api = inject(AuthApiService);
  private readonly store = inject(Store);
  private readonly destroyRef = inject(DestroyRef);
  readonly registered = inject(ActivatedRoute).snapshot.queryParamMap.get('registered') === '1';
  readonly loading = signal(false);
  readonly errorMessage = signal<string | null>(null);
  readonly showPassword = signal(false);
  readonly passwordType = computed(() => (this.showPassword() ? 'text' : 'password'));
  readonly form = inject(FormBuilder).nonNullable.group({
    email: ['', [nonBlank, Validators.email, Validators.maxLength(255)]],
    password: ['', [nonBlank, Validators.maxLength(72), passwordBytes]],
  });

  submit(): void {
    if (this.loading()) return;
    this.errorMessage.set(null);
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.loading.set(true);
    const credentials = this.form.getRawValue();
    this.api
      .login({ ...credentials, email: credentials.email.trim() })
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => {
          this.loading.set(false);
          this.form.controls.password.reset();
        }),
      )
      .subscribe({
        next: (response) => {
          const session =
            response?.tokenType === 'Bearer' ? decodeSession(response.accessToken) : null;
          if (session) this.store.dispatch(AuthActions.loginSuccess({ session }));
          else {
            this.store.dispatch(AuthActions.loginFailure());
            this.errorMessage.set('Unable to sign in. Please try again.');
          }
        },
        error: (error) => {
          this.store.dispatch(AuthActions.loginFailure());
          this.errorMessage.set(authErrorMessage(error, 'login'));
        },
      });
  }
}
