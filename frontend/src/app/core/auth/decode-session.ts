import { AuthSession, Role } from '@app/core/auth/auth.models';

/** UI-only decoding. Signature/issuer verification belongs to Gateway and services. */
export function decodeSession(accessToken: unknown, now = Date.now()): AuthSession | null {
  if (typeof accessToken !== 'string') return null;
  const parts = accessToken.split('.');
  if (parts.length !== 3 || parts.some((part) => !/^[A-Za-z0-9_-]+$/.test(part))) return null;
  try {
    const base64 = parts[1].replace(/-/g, '+').replace(/_/g, '/');
    const bytes = Uint8Array.from(atob(base64.padEnd(Math.ceil(base64.length / 4) * 4, '=')), (c) =>
      c.charCodeAt(0),
    );
    const claims: unknown = JSON.parse(new TextDecoder().decode(bytes));
    if (!claims || typeof claims !== 'object') return null;
    const { sub, roles, exp } = claims as Record<string, unknown>;
    if (
      typeof sub !== 'string' ||
      !/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(sub) ||
      !Array.isArray(roles) ||
      !roles.length ||
      !roles.every((role) => role === 'CUSTOMER' || role === 'ADMIN') ||
      typeof exp !== 'number' ||
      !Number.isSafeInteger(exp) ||
      exp <= now / 1000 ||
      !Number.isSafeInteger(exp * 1000)
    )
      return null;
    return {
      accessToken,
      userId: sub,
      roles: [...new Set(roles)] as Role[],
      expiresAt: exp * 1000,
    };
  } catch {
    return null;
  }
}
