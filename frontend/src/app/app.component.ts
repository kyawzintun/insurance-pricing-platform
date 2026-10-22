import { Component, computed, inject } from '@angular/core';
import { RouterLink, RouterOutlet } from '@angular/router';
import { Store } from '@ngrx/store';
import { authFeature } from '@app/store/auth/auth.reducer';
import { SessionService } from '@app/core/auth/session.service';

@Component({
  selector: 'app-root',
  imports: [RouterLink, RouterOutlet],
  templateUrl: './app.component.html',
  styleUrl: './app.component.css',
})
export class AppComponent {
  private readonly store = inject(Store);
  private readonly session = inject(SessionService);
  readonly authenticated = this.store.selectSignal(authFeature.selectIsAuthenticated);
  readonly roles = this.store.selectSignal(authFeature.selectRoles);
  readonly customer = computed(() => this.roles().includes('CUSTOMER'));
  readonly admin = computed(() => this.roles().includes('ADMIN'));

  logout(): void {
    this.session.logout();
  }
}
