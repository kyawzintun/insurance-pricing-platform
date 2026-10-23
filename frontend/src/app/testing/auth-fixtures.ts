import { AuthSession } from '@app/core/auth/auth.models';
export const USER_ID = '00000000-0000-4000-8000-000000000001';
export function token(claims: object = { sub: USER_ID, roles: ['CUSTOMER'], exp: Math.floor(Date.now() / 1000) + 900 }): string {
  return `${btoa('{"alg":"RS256"}').replace(/=/g, '')}.${btoa(JSON.stringify(claims)).replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_')}.test-signature`;
}
export function session(): AuthSession {
  return { accessToken: token(), userId: USER_ID, roles: ['CUSTOMER'], expiresAt: Date.now() + 900_000 };
}
