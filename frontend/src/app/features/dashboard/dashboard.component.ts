import { Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Store } from '@ngrx/store';
import { authFeature } from '@app/store/auth/auth.reducer';

@Component({
  selector: 'app-dashboard',
  imports: [RouterLink],
  template: `
    <section class="dashboard">
      <p class="eyebrow">DASHBOARD</p>
      <h1>{{ isAdmin() ? 'Administrator account' : 'Welcome to your insurance dashboard.' }}</h1>
      <p class="muted">You’re signed in to Insurance Pricing Platform.</p>
      <div class="card dashboard-card">
        <h2>Your account is ready</h2>
        @if (roles().includes('CUSTOMER')) {
          <p>
            <a routerLink="/quotes/new">Create a new quote</a> or
            <a routerLink="/quotes">view your quotes</a>.
          </p>
        } @else {
          <p>
            Customer quote tools require a CUSTOMER account. Administration tools are planned for a
            later phase.
          </p>
        }
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
