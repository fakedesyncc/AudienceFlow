import {
  Activity,
  Building2,
  Camera,
  Clock3,
  DoorOpen,
  Eye,
  Gauge,
  KeyRound,
  LogOut,
  MapPin,
  Plus,
  RadioTower,
  RefreshCw,
  Search,
  Server,
  ShieldCheck,
  Smartphone,
  UserRound,
  Users,
  Wifi,
  WifiOff,
  Wrench,
} from 'lucide-react';
import { FormEvent, ReactNode, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Area,
  AreaChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import {
  createPresentationSession,
  createCamera,
  createRoom,
  createUser,
  fetchCameras,
  fetchCurrent,
  fetchRooms,
  fetchTimeline,
  fetchUsers,
  loadRuntimeConfig,
  liveSocketUrl,
  login,
  parseLiveMessage,
  saveRuntimeConfig,
  updateCamera,
} from './api';
import type {
  AuthSession,
  Camera as CameraType,
  CreateCameraPayload,
  CreateRoomPayload,
  CreateUserPayload,
  CurrentAttendance,
  Role,
  Room,
  RuntimeConfig,
  TimelinePoint,
  UserView,
} from './types';

type View = 'monitoring' | 'overview' | 'rooms' | 'cameras' | 'mobile' | 'users';
type LiveState = 'presentation' | 'polling' | 'live' | 'offline';

interface TelemetryEvent {
  id: string;
  ts: Date;
  title: string;
  detail: string;
  status: CurrentAttendance['status'] | 'info';
}

interface PreviewState {
  ready: boolean;
  source: string;
  detector: string;
  count: number;
  confidence: number;
  fps: number;
  updated_at: string | null;
}

const sessionStorageKey = 'audienceflow.session';
const roleLabels: Record<Role, string> = {
  ADMIN: 'Администратор',
  TECHNICIAN: 'Техник',
  TEACHER: 'Преподаватель',
};

const cameraSourcePresets: Array<{
  id: string;
  label: string;
  sourceUrl: string;
  streamType: CameraType['streamType'];
}> = [
  { id: 'sample', label: 'Public sample video', sourceUrl: 'sample', streamType: 'sample' },
  { id: 'device0', label: 'Камера компьютера device:0', sourceUrl: 'device:0', streamType: 'device' },
  { id: 'phone', label: 'Телефон как IP/MJPEG камера', sourceUrl: 'phone:http://192.168.1.10:8080/video', streamType: 'mjpeg' },
  { id: 'rtsp', label: 'RTSP камера', sourceUrl: 'rtsp://camera-host/live', streamType: 'rtsp' },
  { id: 'file', label: 'Локальный видеофайл', sourceUrl: '/absolute/path/to/video.mp4', streamType: 'file' },
];

export function App() {
  const [session, setSession] = useState<AuthSession | null>(() => restoreSession());
  const [runtimeConfig, setRuntimeConfig] = useState<RuntimeConfig>(() => loadRuntimeConfig());
  const [activeView, setActiveView] = useState<View>('monitoring');
  const [current, setCurrent] = useState<CurrentAttendance[]>([]);
  const [timeline, setTimeline] = useState<TimelinePoint[]>([]);
  const [rooms, setRooms] = useState<Room[]>([]);
  const [cameras, setCameras] = useState<CameraType[]>([]);
  const [users, setUsers] = useState<UserView[]>([]);
  const [telemetry, setTelemetry] = useState<TelemetryEvent[]>([]);
  const [liveState, setLiveState] = useState<LiveState>('offline');
  const [lastSnapshotAt, setLastSnapshotAt] = useState<Date | null>(null);
  const [selectedRoomId, setSelectedRoomId] = useState<number | null>(null);
  const [selectedMonitoringRoomId, setSelectedMonitoringRoomId] = useState<number | null>(null);
  const [monitorBuilding, setMonitorBuilding] = useState('all');
  const [monitorStatus, setMonitorStatus] = useState<'all' | CurrentAttendance['status']>('all');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const canManageInfrastructure = session?.user.role === 'ADMIN' || session?.user.role === 'TECHNICIAN';
  const isAdmin = session?.user.role === 'ADMIN';

  const loadData = useCallback(async () => {
    if (!session) {
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const [currentData, roomData, cameraData] = await Promise.all([
        fetchCurrent(session),
        fetchRooms(session),
        fetchCameras(session),
      ]);
      applySnapshot(currentData, session.demo ? 'presentation' : 'polling');
      setRooms(roomData);
      setCameras(cameraData);
      if (session.user.role === 'ADMIN') {
        setUsers(await fetchUsers(session));
      }
      if (selectedRoomId === null && roomData.length > 0) {
        setSelectedRoomId(roomData[0].id);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Не удалось загрузить данные');
    } finally {
      setLoading(false);
    }
  }, [selectedRoomId, session]);

  useEffect(() => {
    if (!session) {
      return;
    }
    void loadData();
    const interval = window.setInterval(() => void loadData(), session.demo ? 2500 : 5000);
    return () => window.clearInterval(interval);
  }, [loadData, session]);

  useEffect(() => {
    if (!session || selectedRoomId === null) {
      return;
    }
    fetchTimeline(session, selectedRoomId)
      .then(setTimeline)
      .catch((err) => setError(err instanceof Error ? err.message : 'Не удалось загрузить график'));
  }, [selectedRoomId, session]);

  useEffect(() => {
    if (!session) {
      return;
    }
    const url = liveSocketUrl(session);
    if (!url) {
      return;
    }
    const socket = new WebSocket(url);
    socket.onopen = () => setLiveState('live');
    socket.onmessage = (event) => {
      const message = parseLiveMessage(event.data);
      applySnapshot(message.rooms, 'live');
    };
    socket.onerror = () => {
      setLiveState('polling');
      setError('Live-подключение временно недоступно, включён резервный polling');
    };
    socket.onclose = () => setLiveState('polling');
    return () => socket.close();
  }, [session]);

  useEffect(() => {
    if (current.length === 0) {
      setSelectedMonitoringRoomId(null);
      return;
    }
    if (selectedMonitoringRoomId === null || !current.some((item) => item.roomId === selectedMonitoringRoomId)) {
      setSelectedMonitoringRoomId(current[0].roomId);
    }
  }, [current, selectedMonitoringRoomId]);

  const panelStats = useMemo(() => buildStats(current, cameras), [current, cameras]);

  if (!session) {
    return (
      <LoginScreen
        config={runtimeConfig}
        onConfigChange={(config) => setRuntimeConfig(saveRuntimeConfig(config))}
        onLogin={setSession}
      />
    );
  }

  const navigation = [
    { id: 'monitoring' as View, label: 'Оперативно', icon: RadioTower, enabled: true },
    { id: 'overview' as View, label: 'Аналитика', icon: Activity, enabled: true },
    { id: 'rooms' as View, label: 'Аудитории', icon: DoorOpen, enabled: true },
    { id: 'cameras' as View, label: 'Камеры', icon: Camera, enabled: true },
    { id: 'mobile' as View, label: 'Mobile', icon: Smartphone, enabled: true },
    { id: 'users' as View, label: 'Доступ', icon: Users, enabled: isAdmin },
  ].filter((item) => item.enabled);

  function handleLogout() {
    window.sessionStorage.removeItem(sessionStorageKey);
    setSession(null);
  }

  function applySnapshot(items: CurrentAttendance[], state: LiveState) {
    const receivedAt = new Date();
    setCurrent(items);
    setLastSnapshotAt(receivedAt);
    setLiveState(state);
    setTelemetry((previous) => buildTelemetryEvents(items, receivedAt, previous));
  }

  async function handleCreateRoom(payload: CreateRoomPayload) {
    if (!session) {
      return;
    }
    const room = await createRoom(session, payload);
    setRooms((items) => [...items, room]);
    setSelectedRoomId(room.id);
    await loadData();
  }

  async function handleCreateCamera(payload: CreateCameraPayload) {
    if (!session) {
      return;
    }
    const camera = await createCamera(session, payload);
    setCameras((items) => [...items, camera]);
    await loadData();
  }

  async function handleUpdateCamera(id: number, payload: CreateCameraPayload) {
    if (!session) {
      return;
    }
    const camera = await updateCamera(session, id, payload);
    setCameras((items) => items.map((item) => (item.id === id ? camera : item)));
    await loadData();
  }

  async function handleCreateUser(payload: CreateUserPayload) {
    if (!session) {
      return;
    }
    const user = await createUser(session, payload);
    setUsers((items) => [...items, user]);
  }

  return (
    <div className="app-shell">
      <aside className="app-sidebar">
        <div className="sidebar-brand">
          <div className="brand">
            <div className="brand-mark">
              <Gauge size={22} />
            </div>
            <div>
              <strong>AudienceFlow</strong>
              <span>ЛГТУ · мониторинг аудиторий</span>
            </div>
          </div>
        </div>

        <nav className="nav-list" aria-label="Разделы системы">
          {navigation.map((item) => {
            const Icon = item.icon;
            return (
              <button
                key={item.id}
                className={activeView === item.id ? 'nav-item active' : 'nav-item'}
                onClick={() => setActiveView(item.id)}
                title={item.label}
              >
                <Icon size={18} />
                <span>{item.label}</span>
              </button>
            );
          })}
        </nav>

        <div className="sidebar-state">
          <LiveBadge state={liveState} />
          <span>{lastSnapshotAt ? `Снимок ${formatClock(lastSnapshotAt)}` : 'Ожидание данных'}</span>
          <small>{session.demo ? 'Презентационный контур без рабочих секретов' : session.apiUrl}</small>
        </div>
      </aside>

      <main className="app-main">
        <header className="topbar">
          <div className="topbar-title">
            <span>AudienceFlow · {session.demo ? 'презентационный контур' : 'API-контур'}</span>
            <h1>{viewTitle(activeView)}</h1>
            <p>
              {session.demo
                ? 'Обезличенный поток для показа интерфейса. Рабочие учётные записи и токены не встроены.'
                : new Intl.DateTimeFormat('ru-RU', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date())}
            </p>
          </div>
          <div className="topbar-actions">
            <RoleBadge role={session.user.role} />
            <div className="profile-chip header-profile">
              <UserRound size={17} />
              <div>
                <strong>{session.user.displayName}</strong>
                <span>{roleLabels[session.user.role]}</span>
              </div>
            </div>
            <button className="icon-button" onClick={() => void loadData()} title="Обновить">
              <RefreshCw size={18} className={loading ? 'spin' : ''} />
            </button>
            <button className="icon-button" onClick={handleLogout} title="Выйти">
              <LogOut size={17} />
            </button>
          </div>
        </header>

        <section className="workspace">
          {error && (
            <div className="error-banner">
              <span>{error}</span>
              <button onClick={() => setError(null)}>Закрыть</button>
            </div>
          )}

          {activeView === 'overview' && (
            <Overview
              current={current}
              timeline={timeline}
              rooms={rooms}
              stats={panelStats}
              selectedRoomId={selectedRoomId}
              onSelectRoom={setSelectedRoomId}
            />
          )}
          {activeView === 'monitoring' && (
            <MonitoringView
              current={current}
              cameras={cameras}
              stats={panelStats}
              telemetry={telemetry}
              liveState={liveState}
              lastSnapshotAt={lastSnapshotAt}
              selectedRoomId={selectedMonitoringRoomId}
              buildingFilter={monitorBuilding}
              statusFilter={monitorStatus}
              onSelectRoom={setSelectedMonitoringRoomId}
              onBuildingChange={setMonitorBuilding}
              onStatusChange={setMonitorStatus}
              onRefresh={loadData}
            />
          )}
          {activeView === 'rooms' && (
            <RoomsView rooms={rooms} canManage={Boolean(canManageInfrastructure)} onCreate={handleCreateRoom} />
          )}
          {activeView === 'cameras' && (
            <CamerasView
              cameras={cameras}
              rooms={rooms}
              canManage={Boolean(canManageInfrastructure)}
              onCreate={handleCreateCamera}
              onUpdate={handleUpdateCamera}
            />
          )}
          {activeView === 'mobile' && <MobileView />}
          {activeView === 'users' && isAdmin && <UsersView users={users} onCreate={handleCreateUser} />}
        </section>
      </main>
    </div>
  );
}

function LoginScreen({
  config,
  onConfigChange,
  onLogin,
}: {
  config: RuntimeConfig;
  onConfigChange: (config: RuntimeConfig) => void;
  onLogin: (session: AuthSession) => void;
}) {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [draftConfig, setDraftConfig] = useState(config);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    setDraftConfig(config);
  }, [config]);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      const savedConfig = saveRuntimeConfig(draftConfig);
      onConfigChange(savedConfig);
      const session = await login(email, password, savedConfig);
      window.sessionStorage.setItem(sessionStorageKey, JSON.stringify(session));
      onLogin(session);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Не удалось войти');
    } finally {
      setSubmitting(false);
    }
  }

  function openPresentation() {
    const savedConfig = saveRuntimeConfig({ ...draftConfig, mode: 'demo' });
    onConfigChange(savedConfig);
    const session = createPresentationSession();
    window.sessionStorage.setItem(sessionStorageKey, JSON.stringify(session));
    onLogin(session);
  }

  return (
    <main className="login-screen">
      <section className="login-panel">
        <div className="brand login-brand">
          <div className="brand-mark">
            <Gauge size={26} />
          </div>
          <div>
            <strong>AudienceFlow</strong>
            <span>{draftConfig.mode === 'demo' ? 'Презентационный мониторинг' : 'Защищённый вход'}</span>
          </div>
        </div>

        <form onSubmit={submit} className="login-form">
          <div className="mode-switch" role="group" aria-label="Режим подключения">
            <button
              type="button"
              className={draftConfig.mode === 'demo' ? 'selected' : ''}
              onClick={() => setDraftConfig({ ...draftConfig, mode: 'demo' })}
            >
              Презентация
            </button>
            <button
              type="button"
              className={draftConfig.mode === 'api' ? 'selected' : ''}
              onClick={() => setDraftConfig({ ...draftConfig, mode: 'api' })}
            >
              API
            </button>
          </div>

          {draftConfig.mode === 'demo' ? (
            <div className="presentation-entry">
              <div className="presentation-icon">
                <Eye size={22} />
              </div>
              <div>
                <strong>Без демонстрационных логинов и паролей</strong>
                <span>Открывается обезличенный мониторинг с имитацией потока данных. Рабочие учётные записи здесь не хранятся.</span>
              </div>
              <button type="button" className="primary-button" onClick={openPresentation}>
                <RadioTower size={18} />
                <span>Открыть мониторинг</span>
              </button>
            </div>
          ) : (
            <>
              <label>
                API URL
                <input
                  value={draftConfig.apiUrl}
                  onChange={(event) => setDraftConfig({ ...draftConfig, apiUrl: event.target.value })}
                  placeholder="https://example.com/api"
                  autoComplete="url"
                  required
                />
              </label>
              <label>
                Email
                <input
                  value={email}
                  onChange={(event) => setEmail(event.target.value)}
                  type="email"
                  autoComplete="username"
                  required
                />
              </label>
              <label>
                Пароль
                <input
                  value={password}
                  onChange={(event) => setPassword(event.target.value)}
                  type="password"
                  minLength={12}
                  autoComplete="current-password"
                  required
                />
              </label>
              {error && <div className="form-error">{error}</div>}
              <button className="primary-button" disabled={submitting}>
                <KeyRound size={18} />
                <span>{submitting ? 'Вход...' : 'Войти'}</span>
              </button>
            </>
          )}
        </form>
      </section>
    </main>
  );
}

function MonitoringView({
  current,
  cameras,
  stats,
  telemetry,
  liveState,
  lastSnapshotAt,
  selectedRoomId,
  buildingFilter,
  statusFilter,
  onSelectRoom,
  onBuildingChange,
  onStatusChange,
  onRefresh,
}: {
  current: CurrentAttendance[];
  cameras: CameraType[];
  stats: DashboardStats;
  telemetry: TelemetryEvent[];
  liveState: LiveState;
  lastSnapshotAt: Date | null;
  selectedRoomId: number | null;
  buildingFilter: string;
  statusFilter: 'all' | CurrentAttendance['status'];
  onSelectRoom: (roomId: number) => void;
  onBuildingChange: (building: string) => void;
  onStatusChange: (status: 'all' | CurrentAttendance['status']) => void;
  onRefresh: () => Promise<void>;
}) {
  const buildings = Array.from(new Set(current.map((item) => item.building))).sort((left, right) =>
    left.localeCompare(right, 'ru'),
  );
  const filteredRooms = current.filter((item) => {
    const buildingMatch = buildingFilter === 'all' || item.building === buildingFilter;
    const statusMatch = statusFilter === 'all' || item.status === statusFilter;
    return buildingMatch && statusMatch;
  });
  const selectedRoom =
    current.find((item) => item.roomId === selectedRoomId) ?? filteredRooms[0] ?? current[0] ?? null;
  const selectedCamera = selectedRoom ? cameras.find((camera) => camera.roomId === selectedRoom.roomId) : null;
  const busiest = [...current].sort((left, right) => right.occupancyPercent - left.occupancyPercent)[0] ?? null;
  const maintenanceCount = cameras.filter((camera) => camera.status === 'maintenance').length;
  const attentionCount = current.filter((item) => item.status !== 'normal').length;
  const onlineCount = cameras.filter((camera) => camera.status === 'online').length;

  return (
    <div className="ops-console">
      <section className="ops-statusbar">
        <div className="ops-title">
          <span>Липецкий государственный технический университет</span>
          <h2>Оперативный центр посещаемости</h2>
        </div>
        <div className="ops-signal-group">
          <LiveBadge state={liveState} />
          <div className="signal-time">
            <Clock3 size={18} />
            <span>{lastSnapshotAt ? formatClock(lastSnapshotAt) : 'ожидание данных'}</span>
          </div>
          <button className="icon-button" onClick={() => void onRefresh()} title="Запросить свежий снимок">
            <RefreshCw size={17} />
          </button>
        </div>
      </section>

      <section className="ops-kpis">
        <Metric icon={<DoorOpen size={20} />} label="Аудиторий в контуре" value={stats.rooms} accent="teal" />
        <Metric icon={<Users size={20} />} label="Людей сейчас" value={stats.totalPeople} accent="blue" />
        <Metric icon={<Activity size={20} />} label="Требуют внимания" value={attentionCount} accent="violet" />
        <Metric icon={<Wrench size={20} />} label="Камер онлайн / сервис" value={`${onlineCount}/${maintenanceCount}`} accent="amber" />
      </section>

      <section className="ops-workspace">
        <div className="panel ops-board">
          <div className="panel-header ops-board-header">
            <div>
              <h2>Аудиторный фонд в реальном времени</h2>
              <span>{busiest ? `Пиковая загрузка сейчас: ${busiest.roomName}` : 'Нет активных измерений'}</span>
            </div>
            <div className="ops-filters">
              <label>
                <span>Корпус</span>
                <select value={buildingFilter} onChange={(event) => onBuildingChange(event.target.value)}>
                  <option value="all">Все корпуса</option>
                  {buildings.map((building) => (
                    <option key={building} value={building}>
                      {building}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                <span>Статус</span>
                <select
                  value={statusFilter}
                  onChange={(event) => onStatusChange(event.target.value as 'all' | CurrentAttendance['status'])}
                >
                  <option value="all">Все статусы</option>
                  <option value="normal">Норма</option>
                  <option value="warning">Высокая загрузка</option>
                  <option value="full">Переполнение</option>
                </select>
              </label>
            </div>
          </div>

          <div className="room-state-table-wrap">
            <table className="room-state-table">
              <thead>
                <tr>
                  <th>Аудитория</th>
                  <th>Корпус</th>
                  <th>Люди</th>
                  <th>Загрузка</th>
                  <th>Сигнал</th>
                </tr>
              </thead>
              <tbody>
                {filteredRooms.map((item) => (
                  <tr className={item.roomId === selectedRoom?.roomId ? 'selected' : ''} key={item.roomId}>
                    <td>
                      <button className="room-link" onClick={() => onSelectRoom(item.roomId)}>
                        <MapPin size={16} />
                        <span>{item.roomName}</span>
                      </button>
                    </td>
                    <td>
                      <span className="muted-text">
                        {item.building}, этаж {item.floor}
                      </span>
                    </td>
                    <td>
                      <strong>
                        {item.count}/{item.capacity}
                      </strong>
                    </td>
                    <td>
                      <div className="occupancy-cell">
                        <div className="progress-track">
                          <div className={`progress-fill ${item.status}`} style={{ width: `${Math.min(100, item.occupancyPercent)}%` }} />
                        </div>
                        <span>{item.occupancyPercent}%</span>
                      </div>
                    </td>
                    <td>
                      <span className={`room-status ${item.status}`}>{statusLabel(item.status)}</span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {filteredRooms.length === 0 && (
              <div className="empty-feed">
                <Search size={18} />
                <span>По выбранным фильтрам нет аудиторий.</span>
              </div>
            )}
          </div>
        </div>

        <aside className="panel room-inspector">
          <div className="panel-header">
            <div>
              <h2>{selectedRoom ? selectedRoom.roomName : 'Аудитория не выбрана'}</h2>
              <span>{selectedRoom ? `${selectedRoom.building}, этаж ${selectedRoom.floor}` : 'Выберите строку в таблице'}</span>
            </div>
            <Server size={20} />
          </div>
          {selectedRoom ? (
            <>
              <div className="inspector-count">
                <strong>{selectedRoom.count}</strong>
                <span>из {selectedRoom.capacity} мест</span>
              </div>
              <div className="progress-track inspector-progress">
                <div className={`progress-fill ${selectedRoom.status}`} style={{ width: `${Math.min(100, selectedRoom.occupancyPercent)}%` }} />
              </div>
              <dl className="inspector-list">
                <div>
                  <dt>Заполненность</dt>
                  <dd>{selectedRoom.occupancyPercent}%</dd>
                </div>
                <div>
                  <dt>Достоверность</dt>
                  <dd>{Math.round(selectedRoom.confidence * 100)}%</dd>
                </div>
                <div>
                  <dt>Последнее измерение</dt>
                  <dd>{selectedRoom.timestamp ? formatClock(new Date(selectedRoom.timestamp)) : 'нет сигнала'}</dd>
                </div>
              </dl>
              <div className="camera-inspector">
                <div className={selectedCamera?.status === 'online' ? 'camera-dot online' : 'camera-dot'}>
                  {selectedCamera?.status === 'online' ? <Wifi size={16} /> : <WifiOff size={16} />}
                </div>
                <div>
                  <strong>{selectedCamera?.name ?? 'Камера не назначена'}</strong>
                  <span>{selectedCamera ? streamLabel(selectedCamera.streamType) : 'Добавляется в разделе камер'}</span>
                </div>
                {selectedCamera && <StatusBadge status={selectedCamera.status} />}
              </div>
            </>
          ) : (
            <div className="empty-feed">Нет данных для выбранного контура.</div>
          )}
        </aside>

        <div className="panel ops-feed-panel">
          <div className="panel-header">
            <div>
              <h2>Лента событий</h2>
              <span>Последние изменения состояния</span>
            </div>
            <RadioTower size={20} />
          </div>
          <div className="telemetry-feed">
            {telemetry.length === 0 ? (
              <div className="empty-feed">Ожидание первого снимка.</div>
            ) : (
              telemetry.map((event) => (
                <article className={`telemetry-event ${event.status}`} key={event.id}>
                  <time>{formatClock(event.ts)}</time>
                  <div>
                    <strong>{event.title}</strong>
                    <span>{event.detail}</span>
                  </div>
                </article>
              ))
            )}
          </div>
        </div>
      </section>
    </div>
  );
}

function Overview({
  current,
  timeline,
  rooms,
  stats,
  selectedRoomId,
  onSelectRoom,
}: {
  current: CurrentAttendance[];
  timeline: TimelinePoint[];
  rooms: Room[];
  stats: DashboardStats;
  selectedRoomId: number | null;
  onSelectRoom: (roomId: number) => void;
}) {
  const chartData = timeline.map((point) => ({
    time: formatTime(point.bucket),
    avg: point.avgCount,
    peak: point.peakCount,
  }));

  return (
    <div className="content-grid">
      <section className="metrics-row">
        <Metric icon={<DoorOpen size={20} />} label="Аудитории" value={stats.rooms} accent="teal" />
        <Metric icon={<Activity size={20} />} label="Средняя загрузка" value={`${stats.averageOccupancy}%`} accent="blue" />
        <Metric icon={<Users size={20} />} label="Людей сейчас" value={stats.totalPeople} accent="violet" />
        <Metric icon={<Wifi size={20} />} label="Камер онлайн" value={stats.onlineCameras} accent="amber" />
      </section>

      <section className="panel chart-panel">
        <div className="panel-header">
          <div>
            <h2>Динамика за 24 часа</h2>
            <span>Бакет 5 минут</span>
          </div>
          <select
            value={selectedRoomId ?? ''}
            onChange={(event) => onSelectRoom(Number(event.target.value))}
            aria-label="Аудитория для графика"
          >
            {rooms.map((room) => (
              <option key={room.id} value={room.id}>
                {room.name}
              </option>
            ))}
          </select>
        </div>
        <div className="chart-wrap">
          <ResponsiveContainer width="100%" height="100%">
            <AreaChart data={chartData} margin={{ top: 8, right: 16, left: -20, bottom: 4 }}>
              <defs>
                <linearGradient id="avgGradient" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="5%" stopColor="#0f9f8f" stopOpacity={0.35} />
                  <stop offset="95%" stopColor="#0f9f8f" stopOpacity={0.04} />
                </linearGradient>
              </defs>
              <CartesianGrid strokeDasharray="3 3" stroke="#d8dee7" />
              <XAxis dataKey="time" tick={{ fontSize: 12 }} minTickGap={24} />
              <YAxis tick={{ fontSize: 12 }} />
              <Tooltip contentStyle={{ borderRadius: 8, border: '1px solid #d8dee7' }} />
              <Area type="monotone" dataKey="avg" stroke="#0f9f8f" fill="url(#avgGradient)" strokeWidth={2} />
              <Area type="monotone" dataKey="peak" stroke="#4568dc" fill="transparent" strokeWidth={2} />
            </AreaChart>
          </ResponsiveContainer>
        </div>
      </section>

      <section className="room-grid">
        {current.map((item) => (
          <OccupancyCard key={item.roomId} item={item} />
        ))}
      </section>
    </div>
  );
}

function RoomsView({
  rooms,
  canManage,
  onCreate,
}: {
  rooms: Room[];
  canManage: boolean;
  onCreate: (payload: CreateRoomPayload) => Promise<void>;
}) {
  const [form, setForm] = useState<CreateRoomPayload>({
    name: '',
    building: 'Главный корпус',
    floor: '1',
    capacity: 30,
  });

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    await onCreate(form);
    setForm({ name: '', building: 'Главный корпус', floor: '1', capacity: 30 });
  }

  return (
    <div className="split-layout">
      <section className="panel">
        <div className="panel-header">
          <h2>Аудитории</h2>
          <span>{rooms.length}</span>
        </div>
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Название</th>
                <th>Корпус</th>
                <th>Этаж</th>
                <th>Вместимость</th>
              </tr>
            </thead>
            <tbody>
              {rooms.map((room) => (
                <tr key={room.id}>
                  <td>{room.name}</td>
                  <td>{room.building}</td>
                  <td>{room.floor}</td>
                  <td>{room.capacity}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      {canManage && (
        <section className="panel form-panel">
          <div className="panel-header">
            <h2>Новая аудитория</h2>
            <Building2 size={20} />
          </div>
          <form onSubmit={submit} className="stacked-form">
            <label>
              Название
              <input value={form.name} onChange={(event) => setForm({ ...form, name: event.target.value })} required />
            </label>
            <label>
              Корпус
              <input
                value={form.building}
                onChange={(event) => setForm({ ...form, building: event.target.value })}
                required
              />
            </label>
            <label>
              Этаж
              <input value={form.floor} onChange={(event) => setForm({ ...form, floor: event.target.value })} required />
            </label>
            <label>
              Вместимость
              <input
                value={form.capacity}
                onChange={(event) => setForm({ ...form, capacity: Number(event.target.value) })}
                type="number"
                min={1}
                required
              />
            </label>
            <button className="primary-button">
              <Plus size={18} />
              <span>Добавить</span>
            </button>
          </form>
        </section>
      )}
    </div>
  );
}

function CamerasView({
  cameras,
  rooms,
  canManage,
  onCreate,
  onUpdate,
}: {
  cameras: CameraType[];
  rooms: Room[];
  canManage: boolean;
  onCreate: (payload: CreateCameraPayload) => Promise<void>;
  onUpdate: (id: number, payload: CreateCameraPayload) => Promise<void>;
}) {
  const emptyForm = useMemo<CreateCameraPayload>(() => ({
    roomId: rooms[0]?.id ?? 1,
    name: '',
    sourceUrl: 'sample',
    streamType: 'sample',
    status: 'offline',
    enabled: true,
  }), [rooms]);
  const [selectedCameraId, setSelectedCameraId] = useState<number | null>(null);
  const [form, setForm] = useState<CreateCameraPayload>({
    roomId: rooms[0]?.id ?? 1,
    name: '',
    sourceUrl: 'sample',
    streamType: 'sample',
    status: 'offline',
    enabled: true,
  });

  useEffect(() => {
    if (rooms.length > 0 && !rooms.some((room) => room.id === form.roomId)) {
      setForm((value) => ({ ...value, roomId: rooms[0].id }));
    }
  }, [form.roomId, rooms]);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (selectedCameraId === null) {
      await onCreate(form);
      setForm({ ...emptyForm, roomId: form.roomId });
      return;
    }
    await onUpdate(selectedCameraId, form);
  }

  function selectCamera(camera: CameraType) {
    setSelectedCameraId(camera.id);
    setForm({
      roomId: camera.roomId,
      name: camera.name,
      sourceUrl: camera.sourceUrl ?? '',
      streamType: camera.streamType,
      status: camera.status,
      enabled: camera.enabled,
    });
  }

  function newCamera() {
    setSelectedCameraId(null);
    setForm({ ...emptyForm, roomId: form.roomId });
  }

  return (
    <div className="split-layout">
      <section className="panel">
        <div className="panel-header">
          <h2>Камеры</h2>
          <span>{cameras.length}</span>
        </div>
        <div className="camera-list">
          {cameras.map((camera) => (
            <button
              type="button"
              className={camera.id === selectedCameraId ? 'camera-row selected' : 'camera-row'}
              key={camera.id}
              onClick={() => selectCamera(camera)}
            >
              <div className="camera-icon">
                {camera.status === 'online' ? <Wifi size={18} /> : <WifiOff size={18} />}
              </div>
              <div>
                <strong>{camera.name}</strong>
                <span>{camera.roomName}</span>
                <code>{camera.sourceUrl ?? 'источник скрыт'}</code>
              </div>
              <StatusBadge status={camera.status} />
            </button>
          ))}
        </div>
      </section>

      {canManage && (
        <section className="panel form-panel">
          <div className="panel-header">
            <div>
              <h2>{selectedCameraId === null ? 'Подключить камеру' : 'Редактировать источник'}</h2>
              <span>Источник отвечает за поток, из которого worker считает людей.</span>
            </div>
            <button type="button" className="icon-button" onClick={newCamera} title="Новая камера">
              <Plus size={17} />
            </button>
          </div>
          <form onSubmit={submit} className="stacked-form">
            <label>
              Быстрый профиль
              <select
                value=""
                onChange={(event) => {
                  const preset = cameraSourcePresets.find((item) => item.id === event.target.value);
                  if (preset) {
                    setForm((value) => ({ ...value, sourceUrl: preset.sourceUrl, streamType: preset.streamType }));
                  }
                }}
              >
                <option value="">Выбрать шаблон источника</option>
                {cameraSourcePresets.map((preset) => (
                  <option key={preset.id} value={preset.id}>
                    {preset.label}
                  </option>
                ))}
              </select>
            </label>
            <label>
              Аудитория
              <select value={form.roomId} onChange={(event) => setForm({ ...form, roomId: Number(event.target.value) })}>
                {rooms.map((room) => (
                  <option key={room.id} value={room.id}>
                    {room.name}
                  </option>
                ))}
              </select>
            </label>
            <label>
              Название
              <input value={form.name} onChange={(event) => setForm({ ...form, name: event.target.value })} required />
            </label>
            <label>
              Источник
              <input
                value={form.sourceUrl}
                onChange={(event) => setForm({ ...form, sourceUrl: event.target.value })}
                placeholder="sample, device:0, phone:http://host/video, rtsp://..."
                required
              />
            </label>
            <label>
              Тип
              <select
                value={form.streamType}
                onChange={(event) => setForm({ ...form, streamType: event.target.value as CameraType['streamType'] })}
              >
                <option value="rtsp">RTSP</option>
                <option value="http">HTTP</option>
                <option value="mjpeg">MJPEG/IP camera</option>
                <option value="device">Device</option>
                <option value="file">File</option>
                <option value="sample">Sample video</option>
                <option value="simulation">Simulation</option>
              </select>
            </label>
            <label>
              Статус
              <select
                value={form.status}
                onChange={(event) => setForm({ ...form, status: event.target.value as CameraType['status'] })}
              >
                <option value="online">Online</option>
                <option value="offline">Offline</option>
                <option value="maintenance">Maintenance</option>
              </select>
            </label>
            <label className="toggle-row">
              <input
                type="checkbox"
                checked={form.enabled}
                onChange={(event) => setForm({ ...form, enabled: event.target.checked })}
              />
              Включена
            </label>
            <button className="primary-button">
              {selectedCameraId === null ? <Plus size={18} /> : <Wrench size={18} />}
              <span>{selectedCameraId === null ? 'Добавить' : 'Сохранить источник'}</span>
            </button>
          </form>
        </section>
      )}
    </div>
  );
}

function MobileView() {
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const localStreamRef = useRef<MediaStream | null>(null);
  const [cameraActive, setCameraActive] = useState(false);
  const [previewUrl, setPreviewUrl] = useState('http://localhost:8090');
  const [previewToken, setPreviewToken] = useState('');
  const [previewState, setPreviewState] = useState<PreviewState | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => () => stopLocalCamera(), []);

  useEffect(() => {
    let cancelled = false;
    async function pollPreview() {
      try {
        const response = await fetch(`${normalizePreviewUrl(previewUrl)}/v1/state`, {
          headers: previewToken ? { 'X-Preview-Token': previewToken } : {},
        });
        if (!response.ok) {
          throw new Error(`Preview HTTP ${response.status}`);
        }
        const state = (await response.json()) as PreviewState;
        if (!cancelled) {
          setPreviewState(state);
          setError(null);
        }
      } catch (err) {
        if (!cancelled) {
          setPreviewState(null);
          setError(err instanceof Error ? err.message : 'Preview недоступен');
        }
      }
    }
    void pollPreview();
    const interval = window.setInterval(() => void pollPreview(), 1500);
    return () => {
      cancelled = true;
      window.clearInterval(interval);
    };
  }, [previewToken, previewUrl]);

  async function startLocalCamera() {
    setError(null);
    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        video: { facingMode: { ideal: 'environment' }, width: { ideal: 1280 }, height: { ideal: 720 } },
        audio: false,
      });
      localStreamRef.current = stream;
      if (videoRef.current) {
        videoRef.current.srcObject = stream;
      }
      setCameraActive(true);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Браузер не дал доступ к камере');
    }
  }

  function stopLocalCamera() {
    localStreamRef.current?.getTracks().forEach((track) => track.stop());
    localStreamRef.current = null;
    if (videoRef.current) {
      videoRef.current.srcObject = null;
    }
    setCameraActive(false);
  }

  const streamSrc = `${normalizePreviewUrl(previewUrl)}/v1/stream.mjpg${previewToken ? `?token=${encodeURIComponent(previewToken)}` : ''}`;

  return (
    <div className="mobile-console">
      <section className="panel mobile-hero-panel">
        <div>
          <span>Mobile companion</span>
          <h2>Телефон, планшет или ноутбук как часть контура</h2>
          <p>
            Для production-сценария телефон подключается как IP/MJPEG/RTSP источник в worker. Эта страница помогает проверить
            камеру устройства и смотреть live-preview без desktop-клиента.
          </p>
        </div>
        <Smartphone size={34} />
      </section>

      <section className="mobile-grid">
        <article className="panel mobile-camera-card">
          <div className="panel-header">
            <div>
              <h2>Камера устройства</h2>
              <span>Локальная проверка разрешений браузера</span>
            </div>
            <Camera size={20} />
          </div>
          <video ref={videoRef} className="device-video" autoPlay playsInline muted />
          <div className="mobile-actions">
            <button type="button" className="primary-button" onClick={() => void startLocalCamera()} disabled={cameraActive}>
              <Camera size={18} />
              <span>Включить</span>
            </button>
            <button type="button" className="icon-button wide-light" onClick={stopLocalCamera}>
              <WifiOff size={17} />
              <span>Остановить</span>
            </button>
          </div>
        </article>

        <article className="panel mobile-preview-card">
          <div className="panel-header">
            <div>
              <h2>Live preview worker</h2>
              <span>MJPEG-поток с рамками детекции</span>
            </div>
            <RadioTower size={20} />
          </div>
          <div className="preview-phone-frame">
            <img src={streamSrc} alt="AudienceFlow preview stream" />
          </div>
          <div className="stacked-form">
            <label>
              Preview URL
              <input value={previewUrl} onChange={(event) => setPreviewUrl(event.target.value)} />
            </label>
            <label>
              Preview token
              <input value={previewToken} onChange={(event) => setPreviewToken(event.target.value)} type="password" />
            </label>
          </div>
          <div className="mobile-stats">
            <Metric icon={<Users size={18} />} label="Людей" value={previewState?.count ?? '-'} accent="blue" />
            <Metric
              icon={<ShieldCheck size={18} />}
              label="Достоверность"
              value={previewState ? `${Math.round(previewState.confidence * 100)}%` : '-'}
              accent="teal"
            />
            <Metric icon={<Activity size={18} />} label="FPS" value={previewState?.fps ?? '-'} accent="amber" />
          </div>
        </article>

        <article className="panel mobile-guide">
          <div className="panel-header">
            <div>
              <h2>Как подключить телефон</h2>
              <span>Без облачных секретов и встроенных паролей</span>
            </div>
            <Server size={20} />
          </div>
          <ol>
            <li>Подключите телефон и компьютер к одной защищённой сети.</li>
            <li>Запустите на телефоне IP camera/MJPEG приложение или RTSP-камеру.</li>
            <li>В разделе камер укажите источник: `phone:http://IP:PORT/video` или `rtsp://IP/live`.</li>
            <li>Запустите worker с `CAMERA_SOURCE` равным этому URL и detector `hog` или `yolo`.</li>
          </ol>
          {error && <div className="form-error">{error}</div>}
        </article>
      </section>
    </div>
  );
}

function UsersView({
  users,
  onCreate,
}: {
  users: UserView[];
  onCreate: (payload: CreateUserPayload) => Promise<void>;
}) {
  const [form, setForm] = useState<CreateUserPayload>({
    email: '',
    displayName: '',
    role: 'TEACHER',
    password: '',
  });

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    await onCreate(form);
    setForm({ email: '', displayName: '', role: 'TEACHER', password: '' });
  }

  return (
    <div className="split-layout">
      <section className="panel">
        <div className="panel-header">
          <h2>Пользователи</h2>
          <span>{users.length}</span>
        </div>
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Имя</th>
                <th>Email</th>
                <th>Роль</th>
                <th>Статус</th>
              </tr>
            </thead>
            <tbody>
              {users.map((user) => (
                <tr key={user.id}>
                  <td>{user.displayName}</td>
                  <td>{user.email}</td>
                  <td>
                    <RoleBadge role={user.role} compact />
                  </td>
                  <td>{user.active ? 'Активен' : 'Отключён'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <section className="panel form-panel">
        <div className="panel-header">
          <h2>Новый пользователь</h2>
          <ShieldCheck size={20} />
        </div>
        <form onSubmit={submit} className="stacked-form">
          <label>
            Имя
            <input
              value={form.displayName}
              onChange={(event) => setForm({ ...form, displayName: event.target.value })}
              required
            />
          </label>
          <label>
            Email
            <input
              value={form.email}
              onChange={(event) => setForm({ ...form, email: event.target.value })}
              type="email"
              required
            />
          </label>
          <label>
            Роль
            <select value={form.role} onChange={(event) => setForm({ ...form, role: event.target.value as Role })}>
              <option value="TEACHER">Преподаватель</option>
              <option value="TECHNICIAN">Техник</option>
              <option value="ADMIN">Администратор</option>
            </select>
          </label>
          <label>
            Пароль
            <div className="inline-control">
              <input
                value={form.password}
                onChange={(event) => setForm({ ...form, password: event.target.value })}
                minLength={14}
                autoComplete="new-password"
                required
              />
              <button
                type="button"
                className="icon-button"
                onClick={() => setForm({ ...form, password: generatePassword() })}
                title="Сгенерировать пароль"
              >
                <KeyRound size={17} />
              </button>
            </div>
          </label>
          <button className="primary-button">
            <Plus size={18} />
            <span>Создать</span>
          </button>
        </form>
      </section>
    </div>
  );
}

function OccupancyCard({ item }: { item: CurrentAttendance }) {
  return (
    <article className={`occupancy-card ${item.status}`}>
      <div className="room-card-header">
        <div>
          <strong>{item.roomName}</strong>
          <span>
            {item.building}, этаж {item.floor}
          </span>
        </div>
        <span className="percent">{item.occupancyPercent}%</span>
      </div>
      <div className="progress-track">
        <div className="progress-fill" style={{ width: `${Math.min(100, item.occupancyPercent)}%` }} />
      </div>
      <div className="room-card-footer">
        <span>
          {item.count} / {item.capacity}
        </span>
        <span>conf {Math.round(item.confidence * 100)}%</span>
      </div>
    </article>
  );
}

function Metric({ icon, label, value, accent }: { icon: ReactNode; label: string; value: ReactNode; accent: string }) {
  return (
    <article className={`metric ${accent}`}>
      <div className="metric-icon">{icon}</div>
      <div>
        <span>{label}</span>
        <strong>{value}</strong>
      </div>
    </article>
  );
}

function RoleBadge({ role, compact = false }: { role: Role; compact?: boolean }) {
  return (
    <span className={`role-badge ${role.toLowerCase()} ${compact ? 'compact' : ''}`}>
      <ShieldCheck size={compact ? 14 : 16} />
      {roleLabels[role]}
    </span>
  );
}

function StatusBadge({ status }: { status: CameraType['status'] }) {
  const labels: Record<CameraType['status'], string> = {
    online: 'онлайн',
    offline: 'нет связи',
    maintenance: 'сервис',
  };
  return <span className={`status-badge ${status}`}>{labels[status]}</span>;
}

function LiveBadge({ state }: { state: LiveState }) {
  const labels: Record<LiveState, string> = {
    live: 'Live WebSocket',
    polling: 'Polling',
    presentation: 'Презентация',
    offline: 'Нет сигнала',
  };
  return (
    <span className={`live-badge ${state}`}>
      <RadioTower size={16} />
      {labels[state]}
    </span>
  );
}

interface DashboardStats {
  rooms: number;
  averageOccupancy: number;
  totalPeople: number;
  onlineCameras: number;
}

function buildStats(current: CurrentAttendance[], cameras: CameraType[]): DashboardStats {
  const averageOccupancy =
    current.length === 0
      ? 0
      : Math.round(current.reduce((sum, item) => sum + item.occupancyPercent, 0) / current.length);
  return {
    rooms: current.length,
    averageOccupancy,
    totalPeople: current.reduce((sum, item) => sum + item.count, 0),
    onlineCameras: cameras.filter((camera) => camera.status === 'online').length,
  };
}

function statusLabel(status: CurrentAttendance['status']): string {
  const labels: Record<CurrentAttendance['status'], string> = {
    normal: 'норма',
    warning: 'высокая',
    full: 'критично',
  };
  return labels[status];
}

function streamLabel(streamType: CameraType['streamType']): string {
  const labels: Record<CameraType['streamType'], string> = {
    rtsp: 'RTSP-поток',
    http: 'HTTP-поток',
    mjpeg: 'MJPEG/IP camera',
    device: 'камера устройства',
    file: 'видеофайл',
    sample: 'sample video',
    simulation: 'симулятор',
  };
  return labels[streamType];
}

function buildTelemetryEvents(
  items: CurrentAttendance[],
  receivedAt: Date,
  previous: TelemetryEvent[],
): TelemetryEvent[] {
  if (items.length === 0) {
    return previous;
  }

  const sorted = [...items].sort((left, right) => right.occupancyPercent - left.occupancyPercent);
  const important = sorted.filter((item) => item.status !== 'normal').slice(0, 3);
  const source = important.length > 0 ? important : sorted.slice(0, 2);
  const next = source.map((item) => ({
    id: `${receivedAt.getTime()}-${item.roomId}`,
    ts: receivedAt,
    title: item.status === 'full' ? 'Критическая загрузка' : item.status === 'warning' ? 'Высокая загрузка' : 'Обновление',
    detail: `${item.roomName}: ${item.count}/${item.capacity}, ${item.occupancyPercent}%`,
    status: item.status === 'normal' ? 'info' as const : item.status,
  }));

  return [...next, ...previous].slice(0, 36);
}

function viewTitle(view: View): string {
  switch (view) {
    case 'monitoring':
      return 'Центр мониторинга';
    case 'rooms':
      return 'Аудиторный фонд';
    case 'cameras':
      return 'Камеры';
    case 'mobile':
      return 'Mobile companion';
    case 'users':
      return 'Доступ';
    default:
      return 'Аналитика';
  }
}

function formatTime(value: string): string {
  return new Intl.DateTimeFormat('ru-RU', { hour: '2-digit', minute: '2-digit' }).format(new Date(value));
}

function formatClock(value: Date): string {
  return new Intl.DateTimeFormat('ru-RU', { hour: '2-digit', minute: '2-digit', second: '2-digit' }).format(value);
}

function normalizePreviewUrl(value: string): string {
  const trimmed = value.trim();
  const withScheme = /^https?:\/\//i.test(trimmed) ? trimmed : `http://${trimmed}`;
  return withScheme.replace(/\/$/, '');
}

function restoreSession(): AuthSession | null {
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

function generatePassword(): string {
  const groups = ['ABCDEFGHJKLMNPQRSTUVWXYZ', 'abcdefghijkmnopqrstuvwxyz', '23456789', '!@#$%'];
  const alphabet = groups.join('');
  const values = new Uint32Array(24);
  crypto.getRandomValues(values);
  const chars = groups.map((group, index) => group[values[index] % group.length]);
  for (let index = groups.length; index < values.length; index += 1) {
    chars.push(alphabet[values[index] % alphabet.length]);
  }
  return chars
    .map((char, index) => ({ char, rank: values[index] }))
    .sort((left, right) => left.rank - right.rank)
    .map((item) => item.char)
    .join('');
}
