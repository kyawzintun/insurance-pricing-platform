import { Component, inject } from '@angular/core';
import { RouterLink, RouterOutlet } from '@angular/router';
import { Store } from '@ngrx/store';
import { authFeature } from '@app/store/auth/auth.reducer';
import { AuthActions } from '@app/store/auth/auth.actions';

@Component({
  selector: 'app-root',
  imports: [RouterLink, RouterOutlet],
  templateUrl: './app.component.html',
  styleUrl: './app.component.css',
})
export class AppComponent {
  private readonly store = inject(Store);
  readonly authenticated = this.store.selectSignal(authFeature.selectIsAuthenticated);
  logout(): void {
    this.store.dispatch(AuthActions.logout());
  }
}
