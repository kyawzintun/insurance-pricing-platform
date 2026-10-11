import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideState, provideStore, Store } from '@ngrx/store';
import { AppComponent } from '@app/app.component';
import { DashboardComponent } from '@app/features/dashboard/dashboard.component';
import { AuthActions } from '@app/store/auth/auth.actions';
import { authFeature } from '@app/store/auth/auth.reducer';
import { session } from '@app/testing/auth-fixtures';

describe('Shell and dashboard', () => {
  beforeEach(() => TestBed.configureTestingModule({ providers: [provideRouter([]), provideStore(), provideState(authFeature)] }));
  it('renders guest navigation and clears shared state from Logout', () => {
    const fixture = TestBed.createComponent(AppComponent); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Create account');
    const store = TestBed.inject(Store); store.dispatch(AuthActions.loginSuccess({ session: session() })); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Dashboard');
    (fixture.nativeElement.querySelector('button') as HTMLButtonElement).click(); fixture.detectChanges();
    expect(store.selectSignal(authFeature.selectAccessToken)()).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Sign in');
  });
  it.each(['CUSTOMER', 'ADMIN'] as const)('renders shared %s role state', role => {
    TestBed.inject(Store).dispatch(AuthActions.loginSuccess({ session: { ...session(), roles: [role] } }));
    const fixture = TestBed.createComponent(DashboardComponent); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain(role === 'ADMIN' ? 'Administrator account' : 'Welcome to your insurance dashboard.');
    expect(fixture.nativeElement.textContent).not.toContain(session().accessToken);
  });
});
