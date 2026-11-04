import { Router } from '@angular/router';
import { AuthStore } from '@app/store/auth/auth.store';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AppComponent } from '@app/app.component';
import { DashboardComponent } from '@app/features/dashboard/dashboard.component';
import { session } from '@app/testing/auth-fixtures';

describe('Shell and dashboard', () => {
  beforeEach(() => TestBed.configureTestingModule({ providers: [provideRouter([])] }));
  it('renders guest navigation and clears shared state from Logout', () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Create account');
    const store = TestBed.inject(AuthStore);
    TestBed.inject(AuthStore).loginSuccess(session());
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Dashboard');
    (fixture.nativeElement.querySelector('button') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(TestBed.inject(AuthStore).accessToken()).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Sign in');
  });
  it.each(['CUSTOMER', 'ADMIN'] as const)('renders shared %s role state', (role) => {
    TestBed.inject(AuthStore).loginSuccess({ ...session(), roles: [role] });
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain(
      role === 'ADMIN' ? 'Administrator account' : 'Welcome to your insurance dashboard.',
    );
    expect(fixture.nativeElement.textContent).not.toContain(session().accessToken);
  });
  beforeEach(() => {
    const router = TestBed.inject(Router, null);
    if (router) {
      vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
      vi.spyOn(router, 'navigate').mockResolvedValue(true);
    }
  });
});
