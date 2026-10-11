import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { provideRouter, Router } from '@angular/router';
import { provideState, provideStore, Store } from '@ngrx/store';
import { Subject } from 'rxjs';
import { LoginComponent } from '@app/features/auth/login/login.component';
import { RegisterComponent } from '@app/features/auth/register/register.component';
import { AuthApiService } from '@app/core/auth/auth-api.service';
import { RegisteredUser, TokenResponse } from '@app/core/auth/auth.models';
import { authFeature } from '@app/store/auth/auth.reducer';
import { token } from '@app/testing/auth-fixtures';

const credentials = { email: 'alex@example.invalid', password: 'fictional-test-password' };

describe('Login page', () => {
  let result: Subject<TokenResponse>; let api: { login: ReturnType<typeof vi.fn> };
  beforeEach(() => {
    result = new Subject<TokenResponse>(); api = { login: vi.fn(() => result) };
    TestBed.configureTestingModule({ imports: [LoginComponent], providers: [provideRouter([]), provideStore(), provideState(authFeature), { provide: AuthApiService, useValue: api }] });
  });
  function page() { const fixture = TestBed.createComponent(LoginComponent); fixture.detectChanges(); return fixture; }
  it('starts idle and changes password visibility locally', () => {
    const fixture = page(); const element: HTMLElement = fixture.nativeElement;
    expect(fixture.componentInstance.loading()).toBe(false);
    expect(element.querySelector('input[autocomplete="current-password"]')?.getAttribute('type')).toBe('password');
    (element.querySelector('button[aria-label="Toggle password visibility"]') as HTMLButtonElement).click(); fixture.detectChanges();
    expect(element.querySelector('input[autocomplete="current-password"]')?.getAttribute('type')).toBe('text');
    expect(TestBed.inject(Store).selectSignal(authFeature.selectIsAuthenticated)()).toBe(false);
  });
  it('does not submit invalid forms and displays validation', () => {
    const fixture = page(); fixture.componentInstance.submit(); fixture.detectChanges();
    expect(api.login).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('Enter a valid email address.');
  });
  it('disables duplicate submission, updates shared state and clears the password', () => {
    const fixture = page(); const component = fixture.componentInstance;
    component.form.setValue(credentials); component.submit(); fixture.detectChanges();
    expect((fixture.nativeElement.querySelector('button[type="submit"]') as HTMLButtonElement).disabled).toBe(true);
    component.submit(); expect(api.login).toHaveBeenCalledTimes(1);
    const accessToken = token(); result.next({ accessToken, tokenType: 'Bearer', expiresIn: 900 }); result.complete(); fixture.detectChanges();
    expect(component.loading()).toBe(false); expect(component.form.controls.password.value).toBe('');
    expect(TestBed.inject(Store).selectSignal(authFeature.selectAccessToken)()).toBe(accessToken);
    expect(TestBed.inject(Store).selectSignal(authFeature.selectIsAuthenticated)()).toBe(true);
  });
  it.each([[401, 'Invalid email or password.'], [0, 'Unable to connect to the server. Please try again.'], [500, 'Unable to connect to the server. Please try again.']])('renders a safe local error for %s', (status, expected) => {
    const fixture = page(); const component = fixture.componentInstance; component.form.setValue(credentials); component.submit();
    result.error(new HttpErrorResponse({ status: status as number, error: { secret: 'raw database exception' } })); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain(expected);
    expect(fixture.nativeElement.textContent).not.toContain('raw database exception');
    expect(component.loading()).toBe(false); expect(component.form.controls.password.value).toBe('');
    expect(TestBed.inject(Store).selectSignal(authFeature.selectIsAuthenticated)()).toBe(false);
  });
  it('rejects malformed successful token responses', () => {
    const fixture = page(); fixture.componentInstance.form.setValue(credentials); fixture.componentInstance.submit();
    result.next({ accessToken: 'broken', tokenType: 'Bearer', expiresIn: 900 }); result.complete(); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Unable to sign in. Please try again.');
    expect(TestBed.inject(Store).selectSignal(authFeature.selectIsAuthenticated)()).toBe(false);
  });
  it('cancels an in-flight request when the page is destroyed', () => {
    const fixture = page(); fixture.componentInstance.form.setValue(credentials); fixture.componentInstance.submit(); fixture.destroy();
    result.next({ accessToken: token(), tokenType: 'Bearer', expiresIn: 900 });
    expect(TestBed.inject(Store).selectSignal(authFeature.selectIsAuthenticated)()).toBe(false);
  });
});

describe('Registration page', () => {
  let result: Subject<RegisteredUser>; let api: { register: ReturnType<typeof vi.fn> };
  const values = { ...credentials, firstName: 'Alex', lastName: 'Morgan' };
  beforeEach(() => {
    result = new Subject<RegisteredUser>(); api = { register: vi.fn(() => result) };
    TestBed.configureTestingModule({ imports: [RegisterComponent], providers: [provideRouter([]), provideStore(), provideState(authFeature), { provide: AuthApiService, useValue: api }] });
  });
  function page() { const fixture = TestBed.createComponent(RegisterComponent); fixture.detectChanges(); return fixture; }
  it('requires names, valid email and backend-compatible password length', () => {
    const fixture = page(); const component = fixture.componentInstance; component.submit();
    expect(api.register).not.toHaveBeenCalled();
    component.form.setValue({ ...values, password: 'short' }); expect(component.form.invalid).toBe(true);
    component.form.setValue({ ...values, firstName: '   ' }); expect(component.form.invalid).toBe(true);
    component.form.setValue({ ...values, password: 'é'.repeat(37) }); expect(component.form.invalid).toBe(true);
    component.form.setValue(values); expect(component.form.valid).toBe(true);
  });
  it('toggles local password visibility', () => {
    const fixture = page();
    (fixture.nativeElement.querySelector('button[aria-label="Toggle password visibility"]') as HTMLButtonElement).click(); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('#register-password').type).toBe('text');
  });
  it('shows a duplicate-email error and resets submission state', () => {
    const fixture = page(); const component = fixture.componentInstance; component.form.setValue(values); component.submit(); fixture.detectChanges();
    expect(component.loading()).toBe(true);
    result.error(new HttpErrorResponse({ status: 409, error: 'SQL secret' })); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain('This email is already registered.');
    expect(fixture.nativeElement.textContent).not.toContain('SQL secret');
    expect(component.loading()).toBe(false); expect(component.form.controls.password.value).toBe('');
  });
  it('redirects to login with success indication without authenticating', () => {
    const router = TestBed.inject(Router); const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    const fixture = page(); const component = fixture.componentInstance; component.form.setValue(values); component.submit(); component.submit();
    expect(api.register).toHaveBeenCalledTimes(1);
    result.next({ id: 'temporary-user' } as RegisteredUser); result.complete();
    expect(navigate).toHaveBeenCalledWith(['/login'], { queryParams: { registered: '1' } });
    expect(component.loading()).toBe(false); expect(component.form.controls.password.value).toBe('');
    expect(TestBed.inject(Store).selectSignal(authFeature.selectIsAuthenticated)()).toBe(false);
  });
  it('renders a safe validation failure', () => {
    const fixture = page(); fixture.componentInstance.form.setValue(values); fixture.componentInstance.submit();
    result.error(new HttpErrorResponse({ status: 400, error: 'internal details' })); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Please check your details and try again.');
  });
});
