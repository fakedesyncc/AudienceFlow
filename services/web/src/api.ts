import type {
  AuthSession,
  Camera,
  CreateCameraPayload,
  CreateRoomPayload,
  CreateUserPayload,
  CurrentAttendance,
  LiveAttendanceMessage,
  Room,
  Role,
  RuntimeConfig,
  TimelinePoint,
  UserView,
} from './types';

const runtimeConfigKey = 'audienceflow.runtime-config';
const defaultApiUrl = normalizeApiUrl(import.meta.env.VITE_API_URL?.trim() ?? '');

export const demoAccounts = [
  { role: 'ADMIN' as Role, email: 'af-admin@example.local', password: 'AF-Demo-Admin-2026!' },
  { role: 'TECHNICIAN' as Role, email: 'af-tech@example.local', password: 'AF-Demo-Tech-2026!' },
  { role: 'TEACHER' as Role, email: 'af-teacher@example.local', password: 'AF-Demo-Teacher-2026!' },
];

let demoRooms: Room[] = [
  { id: 1, name: 'Аудитория 305', building: 'Главный корпус', floor: '3', capacity: 64 },
  { id: 2, name: 'Лекционный зал 101', building: 'Главный корпус', floor: '1', capacity: 120 },
  { id: 3, name: 'Лаборатория ИБ-2', building: 'Технопарк', floor: '2', capacity: 28 },
];

let demoCameras: Camera[] = [
  {
    id: 1,
    roomId: 1,
    roomName: 'Аудитория 305',
    name: 'Симулятор камеры',
    sourceUrl: 'simulation',
    streamType: 'simulation',
    status: 'online',
    enabled: true,
    lastSeenAt: new Date().toISOString(),
  },
  {
    id: 2,
    roomId: 2,
    roomName: 'Лекционный зал 101',
    name: 'RTSP вход',
    sourceUrl: 'rtsp://phone-camera.local:8554/live',
    streamType: 'rtsp',
    status: 'maintenance',
    enabled: true,
    lastSeenAt: null,
  },
];

let demoUsers: UserView[] = demoAccounts.map((account, index) => ({
  id: `demo-${index + 1}`,
  email: account.email,
  displayName:
    account.role === 'ADMIN'
      ? 'Администратор AudienceFlow'
      : account.role === 'TECHNICIAN'
        ? 'Техник AudienceFlow'
        : 'Преподаватель AudienceFlow',
  role: account.role,
  active: true,
}));

export function loadRuntimeConfig(): RuntimeConfig {
  const raw = localStorage.getItem(runtimeConfigKey);
  if (raw) {
    try {
      const parsed = JSON.parse(raw) as RuntimeConfig;
      return {
        mode: parsed.mode === 'api' ? 'api' : 'demo',
        apiUrl: normalizeApiUrl(parsed.apiUrl ?? ''),
      };
    } catch {
      localStorage.removeItem(runtimeConfigKey);
    }
  }

  return defaultApiUrl
    ? { mode: 'api', apiUrl: defaultApiUrl }
    : { mode: 'demo', apiUrl: '' };
}

export function saveRuntimeConfig(config: RuntimeConfig): RuntimeConfig {
  const normalized = {
    mode: config.mode,
    apiUrl: normalizeApiUrl(config.apiUrl),
  };
  localStorage.setItem(runtimeConfigKey, JSON.stringify(normalized));
  return normalized;
}

export async function login(email: string, password: string, config: RuntimeConfig): Promise<AuthSession> {
  if (config.mode === 'demo') {
    const account = demoAccounts.find((candidate) => candidate.email === email && candidate.password === password);
    if (!account) {
      throw new Error('Неверные данные входа');
    }
    const user = demoUsers.find((candidate) => candidate.email === account.email)!;
    return { token: `demo-token-${account.role}`, user, expiresInMinutes: 720, demo: true, apiUrl: null };
  }

  const apiUrl = normalizeApiUrl(config.apiUrl);
  if (!apiUrl) {
    throw new Error('Укажите API URL');
  }
  const session = await requestWithBase<Omit<AuthSession, 'demo' | 'apiUrl'>>(apiUrl, '/auth/login', {
    method: 'POST',
    body: JSON.stringify({ email, password }),
  }, null);
  return { ...session, demo: false, apiUrl };
}

export async function fetchCurrent(session: AuthSession): Promise<CurrentAttendance[]> {
  if (session.demo) {
    return demoRooms.map((room, index) => {
      const count = demoCount(room.id, room.capacity);
      const occupancyPercent = Math.round((count / room.capacity) * 100);
      return {
        roomId: room.id,
        roomName: room.name,
        building: room.building,
        floor: room.floor,
        capacity: room.capacity,
        count,
        confidence: 0.82 + index * 0.03,
        timestamp: new Date().toISOString(),
        occupancyPercent,
        status: occupancyPercent >= 95 ? 'full' : occupancyPercent >= 80 ? 'warning' : 'normal',
      };
    });
  }
  return request<CurrentAttendance[]>('/attendance/current', {}, session);
}

export async function fetchTimeline(session: AuthSession, roomId: number): Promise<TimelinePoint[]> {
  if (session.demo) {
    const now = Date.now();
    return Array.from({ length: 24 }, (_, index) => {
      const bucket = new Date(now - (23 - index) * 5 * 60_000);
      const base = 20 + Math.sin(index / 2) * 9 + roomId * 3;
      const avgCount = Math.max(0, Math.round(base + (index % 4)));
      return {
        bucket: bucket.toISOString(),
        avgCount,
        peakCount: avgCount + 4,
        avgConfidence: 0.86,
      };
    });
  }
  return request<TimelinePoint[]>(`/attendance/timeline?roomId=${roomId}&bucketMinutes=5`, {}, session);
}

export async function fetchRooms(session: AuthSession): Promise<Room[]> {
  if (session.demo) {
    return demoRooms;
  }
  return request<Room[]>('/rooms', {}, session);
}

export async function createRoom(session: AuthSession, payload: CreateRoomPayload): Promise<Room> {
  if (session.demo) {
    const room = { ...payload, id: nextId(demoRooms) };
    demoRooms = [...demoRooms, room];
    return room;
  }
  return request<Room>('/rooms', { method: 'POST', body: JSON.stringify(payload) }, session);
}

export async function fetchCameras(session: AuthSession): Promise<Camera[]> {
  if (session.demo) {
    const canSeeSource = session.user.role === 'ADMIN' || session.user.role === 'TECHNICIAN';
    return demoCameras.map((camera) => (canSeeSource ? camera : { ...camera, sourceUrl: null }));
  }
  return request<Camera[]>('/cameras', {}, session);
}

export async function createCamera(session: AuthSession, payload: CreateCameraPayload): Promise<Camera> {
  if (session.demo) {
    const room = demoRooms.find((candidate) => candidate.id === payload.roomId);
    const camera: Camera = {
      ...payload,
      id: nextId(demoCameras),
      roomName: room?.name ?? 'Неизвестная аудитория',
      lastSeenAt: payload.status === 'online' ? new Date().toISOString() : null,
    };
    demoCameras = [...demoCameras, camera];
    return camera;
  }
  return request<Camera>('/cameras', { method: 'POST', body: JSON.stringify(payload) }, session);
}

export async function fetchUsers(session: AuthSession): Promise<UserView[]> {
  if (session.demo) {
    return demoUsers;
  }
  return request<UserView[]>('/admin/users', {}, session);
}

export async function createUser(session: AuthSession, payload: CreateUserPayload): Promise<UserView> {
  if (session.demo) {
    const user: UserView = {
      id: `demo-${Date.now()}`,
      email: payload.email,
      displayName: payload.displayName,
      role: payload.role,
      active: true,
    };
    demoUsers = [...demoUsers, user];
    return user;
  }
  return request<UserView>('/admin/users', { method: 'POST', body: JSON.stringify(payload) }, session);
}

export function liveSocketUrl(session: AuthSession): string | null {
  if (session.demo || !session.apiUrl) {
    return null;
  }
  const url = new URL(session.apiUrl);
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
  url.pathname = `${url.pathname.replace(/\/$/, '')}/ws/live`;
  url.searchParams.set('token', session.token);
  return url.toString();
}

export function parseLiveMessage(raw: string): LiveAttendanceMessage {
  return JSON.parse(raw) as LiveAttendanceMessage;
}

async function request<T>(path: string, init: RequestInit = {}, session: AuthSession | null): Promise<T> {
  if (!session?.apiUrl) {
    throw new Error('API URL не настроен');
  }
  return requestWithBase<T>(session.apiUrl, path, init, session);
}

async function requestWithBase<T>(
  apiUrl: string,
  path: string,
  init: RequestInit = {},
  session: AuthSession | null,
): Promise<T> {
  const response = await fetch(`${apiUrl}${path}`, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...(session ? { Authorization: `Bearer ${session.token}` } : {}),
      ...init.headers,
    },
  });

  if (!response.ok) {
    let message = `HTTP ${response.status}`;
    try {
      const body = await response.json();
      message = body.message ?? message;
    } catch {
      // Keep fallback message.
    }
    throw new Error(message);
  }

  return response.json() as Promise<T>;
}

function normalizeApiUrl(value: string): string {
  return value.trim().replace(/\/$/, '');
}

function nextId<T extends { id: number }>(items: T[]): number {
  return items.length === 0 ? 1 : Math.max(...items.map((item) => item.id)) + 1;
}

function demoCount(roomId: number, capacity: number): number {
  const wave = Math.sin(Date.now() / 45_000 + roomId) * 0.25 + 0.52;
  return Math.max(0, Math.min(capacity, Math.round(capacity * wave)));
}
