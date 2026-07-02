export type Role = 'TEACHER' | 'TECHNICIAN' | 'ADMIN';
export type RuntimeMode = 'demo' | 'api';

export interface RuntimeConfig {
  mode: RuntimeMode;
  apiUrl: string;
}

export interface UserView {
  id: string;
  email: string;
  displayName: string;
  role: Role;
  active: boolean;
}

export interface AuthSession {
  token: string;
  user: UserView;
  expiresInMinutes: number;
  demo: boolean;
  apiUrl: string | null;
}

export interface Room {
  id: number;
  name: string;
  building: string;
  floor: string;
  capacity: number;
  createdAt?: string;
  updatedAt?: string;
}

export interface CurrentAttendance {
  roomId: number;
  roomName: string;
  building: string;
  floor: string;
  capacity: number;
  count: number;
  confidence: number;
  timestamp: string | null;
  occupancyPercent: number;
  status: 'normal' | 'warning' | 'full';
}

export interface TimelinePoint {
  bucket: string;
  avgCount: number;
  peakCount: number;
  avgConfidence: number;
}

export interface Camera {
  id: number;
  roomId: number;
  roomName: string;
  name: string;
  sourceUrl: string | null;
  streamType: 'rtsp' | 'http' | 'mjpeg' | 'device' | 'file' | 'sample' | 'simulation';
  status: 'online' | 'offline' | 'maintenance';
  enabled: boolean;
  lastSeenAt: string | null;
}

export interface CreateRoomPayload {
  name: string;
  building: string;
  floor: string;
  capacity: number;
}

export interface CreateCameraPayload {
  roomId: number;
  name: string;
  sourceUrl: string;
  streamType: Camera['streamType'];
  status: Camera['status'];
  enabled: boolean;
}

export interface CreateUserPayload {
  email: string;
  displayName: string;
  role: Role;
  password: string;
}

export interface LiveAttendanceMessage {
  generatedAt: string;
  rooms: CurrentAttendance[];
}

export interface CampusBuilding {
  id: number;
  code: string;
  name: string;
  address: string;
  roomRanges: string;
  mapX: number;
  mapY: number;
  color: string;
  sourceUrl: string | null;
  updatedAt?: string;
}

export interface ScheduleDirectoryEntity {
  id: number;
  name: string;
  detail: string;
}

export interface ScheduleDirectory {
  groups: ScheduleDirectoryEntity[];
  teachers: ScheduleDirectoryEntity[];
  disciplines: ScheduleDirectoryEntity[];
}

export interface ScheduleEntry {
  id: number;
  date: string;
  weekday: number;
  weekType: 'any' | 'odd' | 'even' | 'green' | 'white';
  startsAt: string;
  endsAt: string;
  lessonType: string;
  subgroup: string;
  roomId: number;
  roomName: string;
  building: string;
  floor: string;
  buildingId: number | null;
  buildingCode: string | null;
  buildingName: string | null;
  groupId: number;
  groupName: string;
  institute: string;
  teacherId: number;
  teacherName: string;
  department: string;
  disciplineId: number;
  disciplineName: string;
  capacity: number;
  actualCount: number | null;
  occupancyPercent: number | null;
  confidence: number | null;
  measuredAt: string | null;
}

export interface ScheduleAnalyticsRow {
  dimension: 'teacher' | 'discipline' | 'group';
  id: number;
  name: string;
  lessons: number;
  plannedCapacity: number;
  measuredLessons: number;
  averageAttendance: number;
  peakAttendance: number;
  averageOccupancyPercent: number;
  averageConfidence: number;
}

export interface ScheduleImportResult {
  parsedRows: number;
  importedRows: number;
  skippedRows: number;
  warnings: string[];
}

export interface TeacherAccessVerification {
  verified: boolean;
  teacherId: number;
  teacherName: string;
}

export interface TeacherKeyIssueResponse {
  teacherId: number;
  teacherName: string;
  accessKey: string;
}
