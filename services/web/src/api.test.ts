import { describe, expect, it } from 'vitest';
import { fetchCampusBuildings, fetchSchedule, liveSocketUrl, parseLiveMessage } from './api';
import type { AuthSession } from './types';

function apiSession(overrides: Partial<AuthSession> = {}): AuthSession {
  return {
    token: 'token-123',
    user: { id: 'u1', email: 'a@b.c', displayName: 'A', role: 'ADMIN', active: true },
    expiresInMinutes: 60,
    demo: false,
    apiUrl: 'https://example.edu/api',
    ...overrides,
  };
}

describe('parseLiveMessage', () => {
  it('parses a valid frame with a rooms array', () => {
    const raw = JSON.stringify({ generatedAt: '2026-07-01T00:00:00Z', rooms: [] });
    const result = parseLiveMessage(raw);
    expect(result).not.toBeNull();
    expect(Array.isArray(result?.rooms)).toBe(true);
  });

  it('returns null for malformed JSON', () => {
    expect(parseLiveMessage('{not valid json')).toBeNull();
  });

  it('returns null when rooms is missing', () => {
    expect(parseLiveMessage(JSON.stringify({ generatedAt: 'x' }))).toBeNull();
  });

  it('returns null when rooms is not an array', () => {
    expect(parseLiveMessage(JSON.stringify({ rooms: 'nope' }))).toBeNull();
  });

  it('returns null for non-object JSON', () => {
    expect(parseLiveMessage('42')).toBeNull();
    expect(parseLiveMessage('null')).toBeNull();
  });
});

describe('liveSocketUrl', () => {
  it('returns null for demo sessions', () => {
    expect(liveSocketUrl(apiSession({ demo: true, apiUrl: null }))).toBeNull();
  });

  it('returns null when apiUrl is absent', () => {
    expect(liveSocketUrl(apiSession({ apiUrl: null }))).toBeNull();
  });

  it('converts https to wss and appends the live path with token', () => {
    const url = new URL(liveSocketUrl(apiSession()) as string);
    expect(url.protocol).toBe('wss:');
    expect(url.pathname).toBe('/api/ws/live');
    expect(url.searchParams.get('token')).toBe('token-123');
  });

  it('converts http to ws', () => {
    const url = new URL(liveSocketUrl(apiSession({ apiUrl: 'http://example.edu' })) as string);
    expect(url.protocol).toBe('ws:');
    expect(url.pathname).toBe('/ws/live');
  });
});

describe('campus demo data', () => {
  it('contains the full LGTU campus map directory', async () => {
    const buildings = await fetchCampusBuildings(apiSession({ demo: true, apiUrl: null }));

    expect(buildings).toHaveLength(13);
    expect(buildings.some((building) => building.code === 'K5' && building.roomRanges.includes('504-514'))).toBe(true);
    expect(buildings.some((building) => building.code === 'AUD' && building.roomRanges.includes('Л-4'))).toBe(true);
  });

  it('does not invent attendance for future timetable dates', async () => {
    const schedule = await fetchSchedule(apiSession({ demo: true, apiUrl: null }), {
      date: '2099-07-06',
    });

    expect(schedule.length).toBeGreaterThan(0);
    expect(schedule.every((entry) => entry.actualCount === null)).toBe(true);
    expect(schedule.every((entry) => entry.occupancyPercent === null)).toBe(true);
  });
});
