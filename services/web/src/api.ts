import type {
  AuthSession,
  Camera,
  CampusBuilding,
  CreateCameraPayload,
  CreateRoomPayload,
  CreateUserPayload,
  CurrentAttendance,
  LiveAttendanceMessage,
  Room,
  RuntimeConfig,
  ScheduleAnalyticsRow,
  ScheduleDirectory,
  ScheduleEntry,
  ScheduleImportResult,
  TeacherAccessVerification,
  TeacherKeyIssueResponse,
  TimelinePoint,
  UserView,
} from './types';
import { demoContourEnabled, publicContour, workContourEnabled } from './runtime';

const runtimeConfigKey = `audienceflow.runtime-config.${publicContour}`;
const defaultApiUrl = normalizeApiUrl(import.meta.env.VITE_API_URL?.trim() ?? '');
const demoModuleLoader = import.meta.env.VITE_PUBLIC_CONTOUR === 'work'
  ? null
  : () => import('./demoData');

async function demoApi() {
  if (!demoModuleLoader) {
    throw new Error('Презентационные данные недоступны в рабочем контуре');
  }
  return demoModuleLoader();
}

export function loadRuntimeConfig(): RuntimeConfig {
  if (demoContourEnabled) {
    return { mode: 'demo', apiUrl: '' };
  }

  const raw = localStorage.getItem(runtimeConfigKey);
  if (raw) {
    try {
      const parsed = JSON.parse(raw) as RuntimeConfig;
      return {
        mode: 'api',
        apiUrl: normalizeApiUrl(parsed.apiUrl ?? ''),
      };
    } catch {
      localStorage.removeItem(runtimeConfigKey);
    }
  }

  return { mode: 'api', apiUrl: defaultApiUrl };
}

export function saveRuntimeConfig(config: RuntimeConfig): RuntimeConfig {
  const normalized = {
    mode: workContourEnabled ? 'api' : config.mode,
    apiUrl: demoContourEnabled ? '' : normalizeApiUrl(config.apiUrl),
  };
  localStorage.setItem(runtimeConfigKey, JSON.stringify(normalized));
  return normalized;
}

export function createPresentationSession(): AuthSession {
  if (!demoContourEnabled) {
    throw new Error('Презентационный режим недоступен в рабочем контуре');
  }

  return {
    token: presentationToken(),
    user: {
      id: 'presentation',
      email: '',
      displayName: 'Просмотр',
      role: 'TEACHER',
      active: true,
    },
    expiresInMinutes: 0,
    demo: true,
    apiUrl: null,
  };
}

function presentationToken(): string {
  if (globalThis.crypto?.randomUUID) {
    return `presentation-${globalThis.crypto.randomUUID()}`;
  }
  return `presentation-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

export async function login(email: string, password: string, config: RuntimeConfig): Promise<AuthSession> {
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
  if (demoContourEnabled && session.demo) {
    return (await demoApi()).fetchDemoCurrent();
  }
  return request<CurrentAttendance[]>('/attendance/current', {}, session)
    .then((items) => items.map(normalizeCurrentAttendance));
}

export async function fetchTimeline(session: AuthSession, roomId: number): Promise<TimelinePoint[]> {
  if (demoContourEnabled && session.demo) {
    return (await demoApi()).fetchDemoTimeline(roomId);
  }
  return request<TimelinePoint[]>(`/attendance/timeline?roomId=${roomId}&bucketMinutes=5`, {}, session);
}

export async function fetchRooms(session: AuthSession): Promise<Room[]> {
  if (demoContourEnabled && session.demo) {
    return (await demoApi()).fetchDemoRooms();
  }
  return request<Room[]>('/rooms', {}, session);
}

export async function createRoom(session: AuthSession, payload: CreateRoomPayload): Promise<Room> {
  if (demoContourEnabled && session.demo) {
    return (await demoApi()).createDemoRoom(payload);
  }
  return request<Room>('/rooms', { method: 'POST', body: JSON.stringify(payload) }, session);
}

export async function fetchCameras(session: AuthSession): Promise<Camera[]> {
  if (demoContourEnabled && session.demo) {
    return (await demoApi()).fetchDemoCameras();
  }
  return request<Camera[]>('/cameras', {}, session);
}

export async function createCamera(session: AuthSession, payload: CreateCameraPayload): Promise<Camera> {
  if (demoContourEnabled && session.demo) {
    return (await demoApi()).createDemoCamera(payload);
  }
  return request<Camera>('/cameras', { method: 'POST', body: JSON.stringify(payload) }, session);
}

export async function updateCamera(session: AuthSession, id: number, payload: CreateCameraPayload): Promise<Camera> {
  if (demoContourEnabled && session.demo) {
    return (await demoApi()).updateDemoCamera(id, payload);
  }
  return request<Camera>(`/cameras/${id}`, { method: 'PUT', body: JSON.stringify(payload) }, session);
}

export async function fetchCampusBuildings(session: AuthSession): Promise<CampusBuilding[]> {
  if (demoContourEnabled && session.demo) {
    return (await demoApi()).fetchDemoCampusBuildings();
  }
  return request<CampusBuilding[]>('/campus/buildings', {}, session);
}

export async function fetchScheduleDirectory(session: AuthSession): Promise<ScheduleDirectory> {
  if (demoContourEnabled && session.demo) {
    return (await demoApi()).fetchDemoScheduleDirectory();
  }
  return request<ScheduleDirectory>('/schedule/directory', {}, session);
}

export async function fetchSchedule(
  session: AuthSession,
  params: { date: string; buildingId?: number | null; teacherId?: number | null; groupId?: number | null },
): Promise<ScheduleEntry[]> {
  if (demoContourEnabled && session.demo) {
    return (await demoApi()).fetchDemoSchedule(params.date, params);
  }
  const search = new URLSearchParams({ date: params.date });
  if (params.buildingId) search.set('buildingId', String(params.buildingId));
  if (params.teacherId) search.set('teacherId', String(params.teacherId));
  if (params.groupId) search.set('groupId', String(params.groupId));
  return request<ScheduleEntry[]>(`/schedule?${search.toString()}`, {}, session)
    .then((items) => items.map(normalizeScheduleEntry));
}

export async function fetchScheduleAnalytics(
  session: AuthSession,
  date: string,
  dimension: ScheduleAnalyticsRow['dimension'],
): Promise<ScheduleAnalyticsRow[]> {
  if (demoContourEnabled && session.demo) {
    return (await demoApi()).fetchDemoScheduleAnalytics(date, dimension);
  }
  const search = new URLSearchParams({ date, dimension });
  return request<ScheduleAnalyticsRow[]>(`/schedule/analytics?${search.toString()}`, {}, session);
}

export async function importScheduleExcel(session: AuthSession, file: File): Promise<ScheduleImportResult> {
  if (demoContourEnabled && session.demo) {
    return {
      parsedRows: 0,
      importedRows: 0,
      skippedRows: 0,
      warnings: ['Импорт Excel доступен только в API-режиме.'],
    };
  }
  const body = new FormData();
  body.append('file', file);
  body.append('source', file.name);
  return request<ScheduleImportResult>('/schedule/import', { method: 'POST', body }, session);
}

export async function verifyTeacherAccess(
  session: AuthSession,
  teacherId: number,
  accessKey: string,
): Promise<TeacherAccessVerification> {
  if (demoContourEnabled && session.demo) {
    return (await demoApi()).verifyDemoTeacherAccess(teacherId, accessKey);
  }
  return request<TeacherAccessVerification>('/teacher-journal/verify', {
    method: 'POST',
    body: JSON.stringify({ teacherId, accessKey }),
  }, session);
}

export async function issueTeacherAccessKey(
  session: AuthSession,
  teacherId: number,
  label: string,
): Promise<TeacherKeyIssueResponse> {
  if (demoContourEnabled && session.demo) {
    return (await demoApi()).issueDemoTeacherAccessKey(teacherId);
  }
  return request<TeacherKeyIssueResponse>('/teacher-journal/keys', {
    method: 'POST',
    body: JSON.stringify({ teacherId, label }),
  }, session);
}

export async function fetchUsers(session: AuthSession): Promise<UserView[]> {
  if (demoContourEnabled && session.demo) {
    return (await demoApi()).fetchDemoUsers();
  }
  return request<UserView[]>('/admin/users', {}, session);
}

export async function createUser(session: AuthSession, payload: CreateUserPayload): Promise<UserView> {
  if (demoContourEnabled && session.demo) {
    throw new Error('Создание пользователей доступно только в API-режиме');
  }
  return request<UserView>('/admin/users', { method: 'POST', body: JSON.stringify(payload) }, session);
}

export function liveSocketUrl(session: AuthSession): string | null {
  if ((demoContourEnabled && session.demo) || !session.apiUrl) {
    return null;
  }
  const url = new URL(session.apiUrl);
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
  url.pathname = `${url.pathname.replace(/\/$/, '')}/ws/live`;
  url.searchParams.set('token', session.token);
  return url.toString();
}

export function parseLiveMessage(raw: string): LiveAttendanceMessage | null {
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return null;
  }
  if (
    typeof parsed !== 'object' ||
    parsed === null ||
    !Array.isArray((parsed as { rooms?: unknown }).rooms)
  ) {
    return null;
  }
  return parsed as LiveAttendanceMessage;
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
  const isFormData = init.body instanceof FormData;
  const response = await fetch(`${apiUrl}${path}`, {
    ...init,
    headers: {
      ...(isFormData ? {} : { 'Content-Type': 'application/json' }),
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

function clampPercent(value: number): number {
  if (!Number.isFinite(value)) {
    return 0;
  }
  return Math.max(0, Math.min(100, Math.round(value)));
}

function normalizeCurrentAttendance(item: CurrentAttendance): CurrentAttendance {
  const occupancyPercent = clampPercent(item.occupancyPercent);
  return {
    ...item,
    occupancyPercent,
    status: occupancyPercent >= 95 ? 'full' : occupancyPercent >= 80 ? 'warning' : 'normal',
  };
}

function normalizeScheduleEntry(item: ScheduleEntry): ScheduleEntry {
  return {
    ...item,
    occupancyPercent: item.occupancyPercent === null ? null : clampPercent(item.occupancyPercent),
  };
}
