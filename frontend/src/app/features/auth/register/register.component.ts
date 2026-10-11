import { Component, computed, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { AuthApiService } from '@app/core/auth/auth-api.service';
import { authErrorMessage } from '@app/core/auth/auth-errors';
import { nonBlank, passwordBytes } from '@app/core/auth/auth.validators';

@Component({
  selector: 'app-register',
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './register.component.html',
})
export class RegisterComponent {
  private readonly api = inject(AuthApiService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  readonly loading = signal(false);
  readonly errorMessage = signal<string | null>(null);
  readonly showPassword = signal(false);
  readonly passwordType = computed(() => (this.showPassword() ? 'text' : 'password'));
  readonly form = inject(FormBuilder).nonNullable.group({
    firstName: ['', [nonBlank, Validators.maxLength(100)]],
    lastName: ['', [nonBlank, Validators.maxLength(100)]],
    email: ['', [nonBlank, Validators.email, Validators.maxLength(255)]],
    password: ['', [nonBlank, Validators.minLength(12), Validators.maxLength(72), passwordBytes]],
  });

  submit(): void {
    if (this.loading()) return;
    this.errorMessage.set(null);
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.loading.set(true);
    const values = this.form.getRawValue();
    this.api
      .register({
        ...values,
        firstName: values.firstName.trim(),
        lastName: values.lastName.trim(),
        email: values.email.trim(),
      })
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => {
          this.loading.set(false);
          this.form.controls.password.reset();
        }),
      )
      .subscribe({
        next: () => {
          void this.router.navigate(['/login'], { queryParams: { registered: '1' } });
        },
        error: (error) => this.errorMessage.set(authErrorMessage(error, 'register')),
      });
  }
}
