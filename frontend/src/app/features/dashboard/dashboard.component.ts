import { Component, computed, inject } from '@angular/core';
import { Store } from '@ngrx/store';
import { authFeature } from '@app/store/auth/auth.reducer';

@Component({
  selector: 'app-dashboard',
  template: `
    <section class="dashboard">
      <p class="eyebrow">DASHBOARD</p>
      <h1>{{ isAdmin() ? 'Administrator account' : 'Welcome to your insurance dashboard.' }}</h1>
      <p class="muted">You’re signed in to Insurance Pricing Platform.</p>
      <div class="card dashboard-card">
        <h2>Your account is ready</h2>
        <p>This is your account home. Quote tools will be available in a future update.</p>
        <p class="role-label">
          Account role: <strong>{{ roles().join(', ') }}</strong>
        </p>
      </div>
      <p class="session-note">For this learning release, refreshing the page signs you out.</p>
    </section>
  `,
})
export class DashboardComponent {
  readonly roles = inject(Store).selectSignal(authFeature.selectRoles);
  readonly isAdmin = computed(() => this.roles().includes('ADMIN'));
}
