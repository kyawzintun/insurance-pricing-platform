import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { FormControl } from '@angular/forms';
import { AuthApiService } from '@app/core/auth/auth-api.service';
import { GATEWAY_URL } from '@app/core/auth/gateway.config';
import { decodeSession } from '@app/core/auth/decode-session';
import { passwordBytes } from '@app/core/auth/auth.validators';
import { token, USER_ID } from '@app/testing/auth-fixtures';

describe('Auth API', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: GATEWAY_URL, useValue: 'http://localhost:8080' },
      ],
    }),
  );
  afterEach(() => TestBed.inject(HttpTestingController).verify());
  it('sends login through Gateway with the exact request contract', () => {
    const body = { email: 'alex@example.invalid', password: 'fictional-test-password' };
    TestBed.inject(AuthApiService).login(body).subscribe();
    const request = TestBed.inject(HttpTestingController).expectOne(
      'http://localhost:8080/api/v1/auth/login',
    );
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual(body);
    if(request.request.url.endsWith('/login')) expect(request.request.withCredentials).toBe(true);
    request.flush({ accessToken: token(), tokenType: 'Bearer', expiresIn: 900 });
  });
  it('sends registration through Gateway without role fields', () => {
    const body = {
      firstName: 'Alex',
      lastName: 'Morgan',
      email: 'alex@example.invalid',
      password: 'fictional-test-password',
    };
    TestBed.inject(AuthApiService).register(body).subscribe();
    const request = TestBed.inject(HttpTestingController).expectOne(
      'http://localhost:8080/api/v1/auth/register',
    );
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual(body);
    if(request.request.url.endsWith('/login')) expect(request.request.withCredentials).toBe(true);
    request.flush({ id: USER_ID }, { status: 201, statusText: 'Created' });
  });
});

describe('UI-only JWT decoding', () => {
  it('extracts user, roles, and expiry for UI state', () => {
    const value = token({ sub: USER_ID, roles: ['ADMIN', 'CUSTOMER'], exp: 2000000000 });
    expect(decodeSession(value, 1000)).toEqual({
      accessToken: value,
      userId: USER_ID,
      roles: ['ADMIN', 'CUSTOMER'],
      expiresAt: 2000000000000,
    });
  });
  it.each([null, '', 'broken', 'a.%%.c', 'a.bnVsbA.c'])(
    'rejects malformed input safely: %s',
    (value) => {
      expect(decodeSession(value)).toBeNull();
    },
  );
  it.each([
    { sub: 'bad', roles: ['CUSTOMER'], exp: 2000000000 },
    { sub: USER_ID, roles: [], exp: 2000000000 },
    { sub: USER_ID, roles: ['OTHER'], exp: 2000000000 },
    { sub: USER_ID, roles: ['CUSTOMER'], exp: 1 },
    { sub: USER_ID, roles: ['CUSTOMER'] },
    { sub: USER_ID, roles: ['CUSTOMER'], exp: '2000000000' },
  ])('rejects unusable claims', (claims) => expect(decodeSession(token(claims))).toBeNull());
  it('validates the UTF-8 byte limit without trimming passwords', () => {
    expect(passwordBytes(new FormControl('é'.repeat(37)))).toEqual({ passwordBytes: true });
    expect(passwordBytes(new FormControl('é'.repeat(36)))).toBeNull();
  });
});
