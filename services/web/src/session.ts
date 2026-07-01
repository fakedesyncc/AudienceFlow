import type { AuthSession } from './types';

export const sessionStorageKey = 'audienceflow.session';

export function restoreSession(): AuthSession | null {
  localStorage.removeItem(sessionStorageKey);
  const raw = window.sessionStorage.getItem(sessionStorageKey);
  if (!raw) {
    return null;
  }
  try {
    const parsed = JSON.parse(raw) as AuthSession;
    if (!parsed.token || !parsed.user || (!parsed.demo && !parsed.apiUrl)) {
      window.sessionStorage.removeItem(sessionStorageKey);
      return null;
    }
    return parsed.demo ? { ...parsed, apiUrl: null } : parsed;
  } catch {
    window.sessionStorage.removeItem(sessionStorageKey);
    return null;
  }
}

export function normalizePreviewUrl(value: string): string {
  const trimmed = value.trim();
  const withScheme = /^https?:\/\//i.test(trimmed) ? trimmed : `http://${trimmed}`;
  return withScheme.replace(/\/$/, '');
}
