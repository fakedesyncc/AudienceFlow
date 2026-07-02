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
  TimelinePoint,
  UserView,
} from './types';

const runtimeConfigKey = 'audienceflow.runtime-config';
const defaultApiUrl = normalizeApiUrl(import.meta.env.VITE_API_URL?.trim() ?? '');

let demoRooms: Room[] = [
  { id: 1, name: 'Аудитория 305', building: 'Корпус 9', floor: '3', capacity: 64 },
  { id: 2, name: 'Лекционный зал 101', building: 'Корпус 1', floor: '1', capacity: 120 },
  { id: 3, name: 'Лаборатория ИКН-2', building: 'Корпус 1', floor: '2', capacity: 32 },
  { id: 4, name: 'Поточная аудитория Б-204', building: 'Корпус Б', floor: '2', capacity: 90 },
  { id: 5, name: 'Лаборатория сетей', building: 'Корпус Б', floor: '3', capacity: 26 },
  { id: 6, name: 'Аудитория 254', building: 'Корпус 2', floor: '2', capacity: 72 },
  { id: 7, name: 'Аудитория 117', building: 'Корпус 3', floor: '1', capacity: 48 },
  { id: 8, name: 'Аудитория 122', building: 'Корпус 4', floor: '1', capacity: 44 },
  { id: 9, name: 'Аудитория 146', building: 'Корпус 5', floor: '1', capacity: 36 },
  { id: 10, name: 'Л-1', building: 'Аудиторный корпус', floor: '1', capacity: 180 },
  { id: 11, name: 'Актовый зал', building: 'Административный корпус', floor: '1', capacity: 220 },
  { id: 12, name: 'Спортзал', building: 'Спортивный комплекс', floor: '1', capacity: 80 },
  { id: 13, name: 'Корпус C · учебная аудитория', building: 'Корпус C', floor: '1', capacity: 40 },
];

let demoCameras: Camera[] = [
  {
    id: 1,
    roomId: 1,
    roomName: 'Аудитория 305',
    name: 'Public sample video',
    sourceUrl: null,
    streamType: 'sample',
    status: 'online',
    enabled: true,
    lastSeenAt: new Date().toISOString(),
  },
  {
    id: 2,
    roomId: 2,
    roomName: 'Лекционный зал 101',
    name: 'IP camera входной зоны',
    sourceUrl: null,
    streamType: 'mjpeg',
    status: 'online',
    enabled: true,
    lastSeenAt: new Date().toISOString(),
  },
  {
    id: 3,
    roomId: 4,
    roomName: 'Поточная аудитория Б-204',
    name: 'Камера поточной аудитории',
    sourceUrl: null,
    streamType: 'rtsp',
    status: 'maintenance',
    enabled: true,
    lastSeenAt: null,
  },
];

const demoBuildings: CampusBuilding[] = [
  campusBuilding(1, 'K1', 'Корпус 1', '100-111, 204-239, 309-346, 408-440', 45, 34, '#D2691E'),
  campusBuilding(2, 'K2', 'Корпус 2', '112-115, 240-263, 347-382, 441-478', 74, 35, '#2F6F7A'),
  campusBuilding(3, 'K3', 'Корпус 3', '117-121, 264-272', 34, 48, '#2E7D5B'),
  campusBuilding(4, 'K4', 'Корпус 4', '122-130, 273-282', 18, 28, '#8F420F'),
  campusBuilding(5, 'K5', 'Корпус 5', '131-146, 283-299, 383-399, 479-499, 504-514', 11, 53, '#245863'),
  campusBuilding(6, 'K9', 'Корпус 9', '9 104-110, 9 206-216, 9 303-315, 9 401, 9 406, 9 408-512', 89, 44, '#6E340C'),
  campusBuilding(7, 'AUD', 'Аудиторный корпус', 'Л-1, Л-2, Л-3, Л-4', 59, 31, '#D2691E'),
  campusBuilding(8, 'ADM', 'Административный корпус', 'Ректорат, профком, актовый зал, деканат', 59, 55, '#2F6F7A'),
  campusBuilding(9, 'DORM', 'Общежитие', 'Медпункт, вход в общежитие', 22, 55, '#8A7C71'),
  campusBuilding(10, 'SPORT', 'Спортивный комплекс', 'Спортзал - 1 этаж, бассейн - 2 этаж', 36, 65, '#2E7D5B'),
  campusBuilding(11, 'CAFE', 'Деревяшка / ВРЕМЯКОФЕ', 'Деревяшка - 3 этаж, ВРЕМЯКОФЕ - 2 этаж', 38, 17, '#B4551A'),
  campusBuilding(12, 'B', 'Корпус Б', 'Отдельный учебный корпус', 72, 80, '#2F6F7A', '398600, Россия, г. Липецк, ул. Интернациональная, д. 5'),
  campusBuilding(13, 'C', 'Корпус C', 'Отдельный учебный корпус', 66, 86, '#8F420F', '398600, Россия, г. Липецк, ул. Интернациональная, д. 5'),
];

demoRooms = expandDemoCampusRooms(demoRooms, demoBuildings);

const demoDirectory: ScheduleDirectory = {
  groups: [
    { id: 1, name: 'ПИ-24-1', detail: 'Институт компьютерных наук' },
    { id: 2, name: 'ПИ-23-1', detail: 'Институт компьютерных наук' },
    { id: 3, name: 'АС-24-1', detail: 'Институт компьютерных наук' },
    { id: 4, name: 'БИ-23-1', detail: 'Институт социальных наук, экономики и права' },
  ],
  teachers: [
    { id: 1, name: 'Ткаченко Светлана Владимировна', detail: 'Прикладная математика и системный анализ' },
    { id: 2, name: 'Богомолова Елена Владимировна', detail: 'Экономика и управление' },
    { id: 3, name: 'Кирсанов Филипп Александрович', detail: 'Транспортные средства и техносферная безопасность' },
  ],
  disciplines: [
    { id: 1, name: 'Дискретная математика', detail: '' },
    { id: 2, name: 'Экономика предприятия', detail: '' },
    { id: 3, name: 'Метрология, стандартизация и сертификация', detail: '' },
    { id: 4, name: 'Проектирование информационных систем', detail: '' },
  ],
};

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

export function createPresentationSession(): AuthSession {
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
  if (session.demo) {
    return demoRooms.map((room, index) => {
      const count = demoCount(room.id, room.capacity);
      const occupancyPercent = clampPercent((count / room.capacity) * 100);
      return {
        roomId: room.id,
        roomName: room.name,
        building: room.building,
        floor: room.floor,
        capacity: room.capacity,
        count,
        confidence: 0.82 + (index % 5) * 0.03,
        timestamp: new Date().toISOString(),
        occupancyPercent,
        status: occupancyPercent >= 95 ? 'full' : occupancyPercent >= 80 ? 'warning' : 'normal',
      };
    });
  }
  return request<CurrentAttendance[]>('/attendance/current', {}, session)
    .then((items) => items.map(normalizeCurrentAttendance));
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
    return demoCameras.map((camera) => ({ ...camera, sourceUrl: null }));
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

export async function updateCamera(session: AuthSession, id: number, payload: CreateCameraPayload): Promise<Camera> {
  if (session.demo) {
    const room = demoRooms.find((candidate) => candidate.id === payload.roomId);
    const updated: Camera = {
      ...payload,
      id,
      roomName: room?.name ?? 'Неизвестная аудитория',
      lastSeenAt: payload.status === 'online' ? new Date().toISOString() : null,
    };
    demoCameras = demoCameras.map((camera) => (camera.id === id ? updated : camera));
    return updated;
  }
  return request<Camera>(`/cameras/${id}`, { method: 'PUT', body: JSON.stringify(payload) }, session);
}

export async function fetchCampusBuildings(session: AuthSession): Promise<CampusBuilding[]> {
  if (session.demo) {
    return demoBuildings;
  }
  return request<CampusBuilding[]>('/campus/buildings', {}, session);
}

export async function fetchScheduleDirectory(session: AuthSession): Promise<ScheduleDirectory> {
  if (session.demo) {
    return demoDirectory;
  }
  return request<ScheduleDirectory>('/schedule/directory', {}, session);
}

export async function fetchSchedule(
  session: AuthSession,
  params: { date: string; buildingId?: number | null; teacherId?: number | null; groupId?: number | null },
): Promise<ScheduleEntry[]> {
  if (session.demo) {
    return demoSchedule(params.date).filter((entry) => {
      const buildingMatch = !params.buildingId || entry.buildingId === params.buildingId;
      const teacherMatch = !params.teacherId || entry.teacherId === params.teacherId;
      const groupMatch = !params.groupId || entry.groupId === params.groupId;
      return buildingMatch && teacherMatch && groupMatch;
    });
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
  if (session.demo) {
    return demoScheduleAnalytics(date, dimension);
  }
  const search = new URLSearchParams({ date, dimension });
  return request<ScheduleAnalyticsRow[]>(`/schedule/analytics?${search.toString()}`, {}, session);
}

export async function importScheduleExcel(session: AuthSession, file: File): Promise<ScheduleImportResult> {
  if (session.demo) {
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
  if (session.demo) {
    const teacher = demoDirectory.teachers.find((item) => item.id === teacherId);
    const verified = isTeacherKeyShape(accessKey);
    return {
      verified,
      teacherId,
      teacherName: teacher?.name ?? 'Преподаватель',
    };
  }
  return request<TeacherAccessVerification>('/teacher-journal/verify', {
    method: 'POST',
    body: JSON.stringify({ teacherId, accessKey }),
  }, session);
}

export async function fetchUsers(session: AuthSession): Promise<UserView[]> {
  if (session.demo) {
    return [];
  }
  return request<UserView[]>('/admin/users', {}, session);
}

export async function createUser(session: AuthSession, payload: CreateUserPayload): Promise<UserView> {
  if (session.demo) {
    throw new Error('Создание пользователей доступно только в API-режиме');
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

function nextId<T extends { id: number }>(items: T[]): number {
  return items.length === 0 ? 1 : Math.max(...items.map((item) => item.id)) + 1;
}

function demoCount(roomId: number, capacity: number): number {
  const wave = Math.sin(Date.now() / 45_000 + roomId) * 0.25 + 0.52;
  return Math.max(0, Math.min(capacity, Math.round(capacity * wave)));
}

function isTeacherKeyShape(value: string): boolean {
  const compact = value.trim();
  return compact.length >= 12 && /[A-Za-zА-Яа-я]/.test(compact) && /\d/.test(compact);
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

function expandDemoCampusRooms(baseRooms: Room[], buildings: CampusBuilding[]): Room[] {
  const rooms = [...baseRooms];
  const seen = new Set(baseRooms.map((room) => demoRoomKey(room.building, room.name)));
  let nextRoomId = nextId(baseRooms);

  buildings.forEach((building) => {
    demoRoomSpecs(building).forEach((spec) => {
      const key = demoRoomKey(building.name, spec.label);
      if (seen.has(key)) {
        return;
      }
      seen.add(key);
      rooms.push({
        id: nextRoomId,
        name: spec.label.match(/^(Л-\d+|9\s+\d+|\d+)$/) ? `Аудитория ${spec.label}` : spec.label,
        building: building.name,
        floor: spec.floor,
        capacity: spec.floor === 'Л' ? 160 : spec.label.startsWith('9 ') ? 64 : 42,
      });
      nextRoomId += 1;
    });
  });

  return rooms;
}

function demoRoomSpecs(building: CampusBuilding): Array<{ label: string; floor: string }> {
  return building.roomRanges
    .split(',')
    .map((token) => token.replace(/[–—]/g, '-').replace(/\s+/g, ' ').trim())
    .filter(Boolean)
    .flatMap((token) => {
      const prefixedRange = token.match(/^9\s*(\d{3})\s*-\s*(\d{3})$/);
      if (building.code === 'K9' && prefixedRange) {
        return demoExpandRange(Number(prefixedRange[1]), Number(prefixedRange[2]))
          .map((room) => ({ label: `9 ${room}`, floor: String(room).slice(0, 1) }));
      }
      const range = token.match(/^(\d{1,3})\s*-\s*(\d{1,3})$/);
      if (range) {
        return demoExpandRange(Number(range[1]), Number(range[2]))
          .map((room) => ({ label: String(room), floor: String(room).slice(0, 1) }));
      }
      const prefixedRoom = token.match(/^9\s*(\d{3})$/);
      if (building.code === 'K9' && prefixedRoom) {
        return [{ label: `9 ${prefixedRoom[1]}`, floor: prefixedRoom[1].slice(0, 1) }];
      }
      const lectureRoom = token.match(/^Л\s*-\s*(\d+)$/i);
      if (lectureRoom) {
        return [{ label: `Л-${lectureRoom[1]}`, floor: 'Л' }];
      }
      return [];
    });
}

function demoExpandRange(start: number, end: number): number[] {
  if (end < start || end - start > 200) {
    return [start];
  }
  return Array.from({ length: end - start + 1 }, (_, index) => start + index);
}

function demoRoomKey(building: string, label: string): string {
  const normalized = `${building}|${label}`.toLocaleLowerCase('ru-RU').replace(/ё/g, 'е');
  const lecture = normalized.match(/л\s*-?\s*(\d+)/);
  if (lecture) {
    return `${building}|л-${lecture[1]}`.toLocaleLowerCase('ru-RU');
  }
  const numbers = normalized.match(/\d+/g);
  if (numbers?.length) {
    return `${building}|${numbers[numbers.length - 1].replace(/^0+/, '')}`;
  }
  return normalized.replace(/[^\p{L}\p{N}|]+/gu, ' ').trim();
}

function campusBuilding(
  id: number,
  code: string,
  name: string,
  roomRanges: string,
  mapX: number,
  mapY: number,
  color: string,
  address = '398055, Россия, г. Липецк, ул. Московская, д. 30',
): CampusBuilding {
  return {
    id,
    code,
    name,
    address,
    roomRanges,
    mapX,
    mapY,
    color,
    sourceUrl: 'https://www.stu.lipetsk.ru/fak/zf/ext/korpus.html',
  };
}

function demoSchedule(date: string): ScheduleEntry[] {
  const roomByName = new Map(demoRooms.map((room) => [room.name, room]));
  const isFutureDate = date > localDateValue(new Date());
  const rows = [
    {
      id: 1,
      weekday: 1,
      startsAt: '09:40:00',
      endsAt: '11:10:00',
      lessonType: 'практика',
      roomName: 'Аудитория 305',
      buildingId: 6,
      buildingCode: 'K9',
      buildingName: 'Корпус 9',
      groupId: 1,
      groupName: 'ПИ-24-1',
      institute: 'Институт компьютерных наук',
      teacherId: 1,
      teacherName: 'Ткаченко Светлана Владимировна',
      department: 'Прикладная математика и системный анализ',
      disciplineId: 1,
      disciplineName: 'Дискретная математика',
    },
    {
      id: 2,
      weekday: 2,
      startsAt: '11:20:00',
      endsAt: '12:50:00',
      lessonType: 'лекция',
      roomName: 'Лекционный зал 101',
      buildingId: 1,
      buildingCode: 'K1',
      buildingName: 'Корпус 1',
      groupId: 2,
      groupName: 'ПИ-23-1',
      institute: 'Институт компьютерных наук',
      teacherId: 1,
      teacherName: 'Ткаченко Светлана Владимировна',
      department: 'Прикладная математика и системный анализ',
      disciplineId: 4,
      disciplineName: 'Проектирование информационных систем',
    },
    {
      id: 3,
      weekday: 3,
      startsAt: '13:20:00',
      endsAt: '14:50:00',
      lessonType: 'лекция',
      roomName: 'Лекционный зал 101',
      buildingId: 1,
      buildingCode: 'K1',
      buildingName: 'Корпус 1',
      groupId: 4,
      groupName: 'БИ-23-1',
      institute: 'Институт социальных наук, экономики и права',
      teacherId: 2,
      teacherName: 'Богомолова Елена Владимировна',
      department: 'Экономика и управление',
      disciplineId: 2,
      disciplineName: 'Экономика предприятия',
    },
    {
      id: 4,
      weekday: 4,
      startsAt: '15:00:00',
      endsAt: '16:30:00',
      lessonType: 'лабораторная',
      roomName: 'Лаборатория ИКН-2',
      buildingId: 1,
      buildingCode: 'K1',
      buildingName: 'Корпус 1',
      groupId: 3,
      groupName: 'АС-24-1',
      institute: 'Институт компьютерных наук',
      teacherId: 3,
      teacherName: 'Кирсанов Филипп Александрович',
      department: 'Транспортные средства и техносферная безопасность',
      disciplineId: 3,
      disciplineName: 'Метрология, стандартизация и сертификация',
    },
    {
      id: 5,
      weekday: 5,
      startsAt: '08:00:00',
      endsAt: '09:30:00',
      lessonType: 'семинар',
      roomName: 'Поточная аудитория Б-204',
      buildingId: 12,
      buildingCode: 'B',
      buildingName: 'Корпус Б',
      groupId: 1,
      groupName: 'ПИ-24-1',
      institute: 'Институт компьютерных наук',
      teacherId: 2,
      teacherName: 'Богомолова Елена Владимировна',
      department: 'Экономика и управление',
      disciplineId: 2,
      disciplineName: 'Экономика предприятия',
    },
  ];
  const weekday = jsIsoWeekday(new Date(`${date}T00:00:00`));
  return rows
    .filter((row) => row.weekday === weekday)
    .map((row) => {
      const room = roomByName.get(row.roomName) ?? demoRooms[0];
      const actualCount = isFutureDate ? null : demoCount(room.id, room.capacity);
      return {
        ...row,
        date,
        weekType: 'any' as const,
        subgroup: '',
        roomId: room.id,
        building: room.building,
        floor: room.floor,
        capacity: room.capacity,
        actualCount,
        occupancyPercent: actualCount === null ? null : clampPercent((actualCount / room.capacity) * 100),
        confidence: actualCount === null ? null : 0.84 + (row.id % 4) * 0.03,
        measuredAt: actualCount === null ? null : new Date().toISOString(),
      };
    });
}

function demoScheduleAnalytics(date: string, dimension: ScheduleAnalyticsRow['dimension']): ScheduleAnalyticsRow[] {
  const schedule = demoSchedule(date);
  const groups = new Map<number, ScheduleEntry[]>();
  for (const entry of schedule) {
    const key = dimension === 'teacher' ? entry.teacherId : dimension === 'discipline' ? entry.disciplineId : entry.groupId;
    groups.set(key, [...(groups.get(key) ?? []), entry]);
  }
  return [...groups.entries()].map(([id, entries]) => {
    const first = entries[0];
    const name = dimension === 'teacher' ? first.teacherName : dimension === 'discipline' ? first.disciplineName : first.groupName;
    const measured = entries.filter((entry) => entry.actualCount !== null);
    const avgAttendance = average(measured.map((entry) => entry.actualCount ?? 0));
    return {
      dimension,
      id,
      name,
      lessons: entries.length,
      plannedCapacity: entries.reduce((sum, entry) => sum + entry.capacity, 0),
      measuredLessons: measured.length,
      averageAttendance: Math.round(avgAttendance),
      peakAttendance: Math.max(...measured.map((entry) => entry.actualCount ?? 0), 0),
      averageOccupancyPercent: Math.round(average(measured.map((entry) => entry.occupancyPercent ?? 0))),
      averageConfidence: Number(average(measured.map((entry) => entry.confidence ?? 0)).toFixed(2)),
    };
  });
}

function jsIsoWeekday(date: Date): number {
  const day = date.getDay();
  return day === 0 ? 7 : day;
}

function localDateValue(date: Date): string {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

function average(values: number[]): number {
  if (values.length === 0) {
    return 0;
  }
  return values.reduce((sum, value) => sum + value, 0) / values.length;
}
