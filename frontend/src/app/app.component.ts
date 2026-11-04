import { AuthStore } from '@app/store/auth/auth.store';
import { Component, inject } from '@angular/core';
import { RouterLink, RouterOutlet } from '@angular/router';
import { SessionService } from '@app/core/auth/session.service';

@Component({
  selector: 'app-root',
  imports: [RouterLink, RouterOutlet],
  templateUrl: './app.component.html',
  styleUrl: './app.component.css',
})
export class AppComponent {
  private readonly store = inject(AuthStore);
  private readonly session = inject(SessionService);
  readonly authenticated = this.store.isAuthenticated;
  readonly roles = this.store.roles;
  readonly customer = this.store.isCustomer;
  readonly admin = this.store.isAdmin;

  logout(): void {
    this.session.logout();
  }
}
