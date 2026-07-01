import { beforeEach, describe, expect, it, vi } from 'vitest';
import { normalizePreviewUrl, restoreSession, sessionStorageKey } from './session';
import type { AuthSession } from './types';

function createStorage(): Storage {
  const map = new Map<string, string>();
  return {
    get length() {
      return map.size;
    },
    clear: () => map.clear(),
    getItem: (key: string) => (map.has(key) ? (map.get(key) as string) : null),
    key: (index: number) => Array.from(map.keys())[index] ?? null,
    removeItem: (key: string) => void map.delete(key),
    setItem: (key: string, value: string) => void map.set(key, value),
  };
}

const validApiSession: AuthSession = {
  token: 'token-123',
  user: { id: 'u1', email: 'a@b.c', displayName: 'A', role: 'ADMIN', active: true },
  expiresInMinutes: 60,
  demo: false,
  apiUrl: 'https://example.edu/api',
};

const demoSession: AuthSession = {
  token: 'presentation-1',
  user: { id: 'presentation', email: '', displayName: 'Просмотр', role: 'TEACHER', active: true },
  expiresInMinutes: 0,
  demo: true,
  apiUrl: null,
};

let sessionStore: Storage;
let localStore: Storage;

beforeEach(() => {
  sessionStore = createStorage();
  localStore = createStorage();
  vi.stubGlobal('localStorage', localStore);
  vi.stubGlobal('window', { sessionStorage: sessionStore });
});

describe('restoreSession', () => {
  it('restores a valid API session', () => {
    sessionStore.setItem(sessionStorageKey, JSON.stringify(validApiSession));
    expect(restoreSession()).toEqual(validApiSession);
  });

  it('returns null and clears storage for an invalid session (missing token)', () => {
    sessionStore.setItem(sessionStorageKey, JSON.stringify({ ...validApiSession, token: '' }));
    expect(restoreSession()).toBeNull();
    expect(sessionStore.getItem(sessionStorageKey)).toBeNull();
  });

  it('returns null for malformed JSON', () => {
    sessionStore.setItem(sessionStorageKey, '{not json');
    expect(restoreSession()).toBeNull();
    expect(sessionStore.getItem(sessionStorageKey)).toBeNull();
  });

  it('returns null when nothing is stored', () => {
    expect(restoreSession()).toBeNull();
  });

  it('restores a demo session and forces apiUrl to null', () => {
    sessionStore.setItem(sessionStorageKey, JSON.stringify({ ...demoSession, apiUrl: 'https://leak' }));
    expect(restoreSession()).toEqual({ ...demoSession, apiUrl: null });
  });

  it('always clears any legacy localStorage copy', () => {
    localStore.setItem(sessionStorageKey, 'legacy');
    restoreSession();
    expect(localStore.getItem(sessionStorageKey)).toBeNull();
  });
});

describe('normalizePreviewUrl', () => {
  it('adds http scheme when missing', () => {
    expect(normalizePreviewUrl('localhost:8090')).toBe('http://localhost:8090');
  });

  it('preserves an existing https scheme', () => {
    expect(normalizePreviewUrl('https://host/')).toBe('https://host');
  });

  it('trims whitespace and a trailing slash', () => {
    expect(normalizePreviewUrl('  http://host:9/  ')).toBe('http://host:9');
  });
});
