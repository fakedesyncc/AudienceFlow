import {
  Activity,
  Building2,
  Camera,
  CheckCircle2,
  ChevronRight,
  Clock3,
  DoorOpen,
  Eye,
  Gauge,
  KeyRound,
  Laptop,
  Layers3,
  LogOut,
  Plus,
  RadioTower,
  RefreshCw,
  Server,
  ShieldCheck,
  Smartphone,
  Sparkles,
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
  createCamera,
  createPresentationSession,
  createRoom,
  createUser,
  fetchCameras,
  fetchCurrent,
  fetchRooms,
  fetchTimeline,
  fetchUsers,
  liveSocketUrl,
  loadRuntimeConfig,
  login,
  parseLiveMessage,
  saveRuntimeConfig,
  updateCamera,
} from './api';
import { normalizePreviewUrl, restoreSession, sessionStorageKey } from './session';
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

type View = 'live' | 'insights' | 'cameras' | 'rooms' | 'mobile' | 'access';
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

interface DashboardStats {
  rooms: number;
  totalPeople: number;
  averageOccupancy: number;
  attentionRooms: number;
  onlineCameras: number;
}

const roleLabels: Record<Role, string> = {
  ADMIN: 'Администратор',
  TECHNICIAN: 'Техник',
  TEACHER: 'Преподаватель',
};

const sourcePresets: Array<{
  id: string;
  label: string;
  note: string;
  sourceUrl: string;
  streamType: CameraType['streamType'];
}> = [
  {
    id: 'sample',
    label: 'Sample video',
    note: 'Публичное видео для защиты без синтетической сцены',
    sourceUrl: 'sample',
    streamType: 'sample',
  },
  {
    id: 'device',
    label: 'Камера ноутбука',
    note: 'Запуск worker на host: CAMERA_SOURCE=device:0',
    sourceUrl: 'device:0',
    streamType: 'device',
  },
  {
    id: 'phone',
    label: 'Телефон как IP camera',
    note: 'MJPEG/RTSP приложение в одной защищённой сети',
    sourceUrl: 'phone:http://192.168.1.10:8080/video',
    streamType: 'mjpeg',
  },
  {
    id: 'rtsp',
    label: 'RTSP камера',
    note: 'Камера аудитории или NVR поток',
    sourceUrl: 'rtsp://camera-host/live',
    streamType: 'rtsp',
  },
  {
    id: 'file',
    label: 'Видео файл',
    note: 'Локальный mp4/mov для тестов и отчёта',
    sourceUrl: '/absolute/path/to/video.mp4',
    streamType: 'file',
  },
];

export function App() {
  const [session, setSession] = useState<AuthSession | null>(() => restoreSession());
  const [runtimeConfig, setRuntimeConfig] = useState<RuntimeConfig>(() => loadRuntimeConfig());
  const [activeView, setActiveView] = useState<View>('live');
  const [current, setCurrent] = useState<CurrentAttendance[]>([]);
  const [timeline, setTimeline] = useState<TimelinePoint[]>([]);
  const [rooms, setRooms] = useState<Room[]>([]);
  const [cameras, setCameras] = useState<CameraType[]>([]);
  const [users, setUsers] = useState<UserView[]>([]);
  const [telemetry, setTelemetry] = useState<TelemetryEvent[]>([]);
  const [liveState, setLiveState] = useState<LiveState>('offline');
  const [lastSnapshotAt, setLastSnapshotAt] = useState<Date | null>(null);
  const [selectedRoomId, setSelectedRoomId] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const liveSocketRef = useRef<WebSocket | null>(null);

  const canManageInfrastructure = session?.user.role === 'ADMIN' || session?.user.role === 'TECHNICIAN';
  const isAdmin = session?.user.role === 'ADMIN';
  const stats = useMemo(() => buildStats(current, cameras), [current, cameras]);
  const selectedRoom = current.find((item) => item.roomId === selectedRoomId) ?? current[0] ?? null;

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
      setRooms(roomData);
      setCameras(cameraData);
      // While a live WebSocket owns 'current', polling must not override it.
      if (liveSocketRef.current?.readyState !== WebSocket.OPEN) {
        applySnapshot(currentData, session.demo ? 'presentation' : 'polling');
      }
      if (session.user.role === 'ADMIN') {
        setUsers(await fetchUsers(session));
      }
      if (selectedRoomId === null && currentData.length > 0) {
        setSelectedRoomId(currentData[0].roomId);
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
    const interval = window.setInterval(() => void loadData(), session.demo ? 2400 : 5000);
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

    let mounted = true;
    let attempt = 0;
    let reconnectTimer: number | undefined;

    function connect() {
      if (!mounted) {
        return;
      }
      const socket = new WebSocket(url as string);
      liveSocketRef.current = socket;

      socket.onopen = () => {
        attempt = 0;
        setLiveState('live');
      };
      socket.onmessage = (event) => {
        const message = parseLiveMessage(event.data);
        if (!message) {
          return;
        }
        applySnapshot(message.rooms, 'live');
      };
      socket.onerror = () => {
        setError('Live-канал временно недоступен, включён polling');
      };
      socket.onclose = () => {
        if (liveSocketRef.current === socket) {
          liveSocketRef.current = null;
        }
        if (!mounted) {
          return;
        }
        setLiveState('polling');
        // Reconnect with capped exponential backoff + jitter so polling
        // hands ownership of 'current' back to the live socket once it recovers.
        const backoff = Math.min(30_000, 1_000 * 2 ** attempt);
        attempt += 1;
        const delay = backoff + Math.random() * 1_000;
        reconnectTimer = window.setTimeout(connect, delay);
      };
    }

    connect();

    return () => {
      mounted = false;
      if (reconnectTimer !== undefined) {
        window.clearTimeout(reconnectTimer);
      }
      const socket = liveSocketRef.current;
      liveSocketRef.current = null;
      socket?.close();
    };
  }, [session]);

  function applySnapshot(items: CurrentAttendance[], state: LiveState) {
    const receivedAt = new Date();
    setCurrent(items);
    setLastSnapshotAt(receivedAt);
    setLiveState(state);
    setTelemetry((previous) => buildTelemetry(items, receivedAt, previous));
  }

  async function handleCreateRoom(payload: CreateRoomPayload) {
    if (!session) {
      return;
    }
    const room = await createRoom(session, payload);
    setRooms((items) => [...items, room]);
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

  function handleLogout() {
    window.sessionStorage.removeItem(sessionStorageKey);
    setSession(null);
  }

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
    { id: 'live' as View, label: 'Live', shortLabel: 'Live', icon: RadioTower },
    { id: 'insights' as View, label: 'Аналитика', shortLabel: 'Графики', icon: Activity },
    { id: 'cameras' as View, label: 'Камеры', shortLabel: 'Камеры', icon: Camera },
    { id: 'rooms' as View, label: 'Аудитории', shortLabel: 'Залы', icon: DoorOpen },
    { id: 'mobile' as View, label: 'Mobile', shortLabel: 'Phone', icon: Smartphone },
    ...(isAdmin ? [{ id: 'access' as View, label: 'Доступ', shortLabel: 'Доступ', icon: ShieldCheck }] : []),
  ];

  return (
    <div className="app-shell">
      <header className="app-topbar">
        <div className="brand-block">
          <div className="brand-mark">
            <Gauge size={22} />
          </div>
          <div>
            <strong>AudienceFlow</strong>
            <span>{session.demo ? 'Презентационный контур' : 'API контур'}</span>
          </div>
        </div>

        <nav className="top-nav" aria-label="Разделы">
          {navigation.map((item) => (
            <NavButton
              key={item.id}
              active={activeView === item.id}
              icon={<item.icon size={18} />}
              label={item.label}
              onClick={() => setActiveView(item.id)}
            />
          ))}
        </nav>

        <div className="session-tools">
          <StatusPill state={liveState} />
          <button className="tool-button" onClick={() => void loadData()} title="Обновить">
            <RefreshCw size={18} className={loading ? 'spin' : ''} />
          </button>
          <button className="profile-button" title={`${session.user.displayName} · ${roleLabels[session.user.role]}`}>
            <UserRound size={17} />
            <span>{roleLabels[session.user.role]}</span>
          </button>
          <button className="tool-button" onClick={handleLogout} title="Выйти">
            <LogOut size={18} />
          </button>
        </div>
      </header>

      <main className="workspace">
        {error && (
          <div className="error-banner">
            <span>{error}</span>
            <button onClick={() => setError(null)}>Закрыть</button>
          </div>
        )}

        {activeView === 'live' && (
          <LiveView
            stats={stats}
            current={current}
            cameras={cameras}
            selectedRoom={selectedRoom}
            onSelectRoom={setSelectedRoomId}
            telemetry={telemetry}
            lastSnapshotAt={lastSnapshotAt}
            liveState={liveState}
          />
        )}
        {activeView === 'insights' && (
          <InsightsView
            stats={stats}
            rooms={rooms}
            current={current}
            timeline={timeline}
            selectedRoomId={selectedRoomId}
            onSelectRoom={setSelectedRoomId}
          />
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
        {activeView === 'rooms' && (
          <RoomsView rooms={rooms} current={current} canManage={Boolean(canManageInfrastructure)} onCreate={handleCreateRoom} />
        )}
        {activeView === 'mobile' && <MobileView />}
        {activeView === 'access' && isAdmin && <AccessView users={users} onCreate={handleCreateUser} />}
      </main>

      <nav className="mobile-nav" aria-label="Мобильная навигация">
        {navigation.map((item) => (
          <button
            key={item.id}
            className={activeView === item.id ? 'active' : ''}
            onClick={() => setActiveView(item.id)}
            title={item.label}
          >
            <item.icon size={19} />
            <span>{item.shortLabel}</span>
          </button>
        ))}
      </nav>
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
  const [draftConfig, setDraftConfig] = useState(config);
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => setDraftConfig(config), [config]);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      const savedConfig = saveRuntimeConfig({ ...draftConfig, mode: 'api' });
      onConfigChange(savedConfig);
      const authSession = await login(email, password, savedConfig);
      window.sessionStorage.setItem(sessionStorageKey, JSON.stringify(authSession));
      onLogin(authSession);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Не удалось войти');
    } finally {
      setSubmitting(false);
    }
  }

  function openPresentation() {
    const savedConfig = saveRuntimeConfig({ ...draftConfig, mode: 'demo' });
    onConfigChange(savedConfig);
    const authSession = createPresentationSession();
    window.sessionStorage.setItem(sessionStorageKey, JSON.stringify(authSession));
    onLogin(authSession);
  }

  return (
    <main className="login-screen">
      <section className="login-workspace">
        <div className="login-visual">
          <div className="brand-block login-brand">
            <div className="brand-mark">
              <Gauge size={24} />
            </div>
            <div>
              <strong>AudienceFlow</strong>
              <span>Real-time аудитории и камеры</span>
            </div>
          </div>
          <DemoScene compact />
        </div>

        <form className="login-card" onSubmit={submit}>
          <span className="eyebrow">Защищённый вход</span>
          <h1>Мониторинг, который можно показать с телефона и с рабочего места</h1>
          <p>Pages не содержит рабочих логинов, паролей или токенов. Для защиты можно открыть презентационный режим или подключить свой API.</p>

          <div className="mode-switch">
            <button type="button" className={draftConfig.mode === 'demo' ? 'selected' : ''} onClick={() => setDraftConfig({ ...draftConfig, mode: 'demo' })}>
              Презентация
            </button>
            <button type="button" className={draftConfig.mode === 'api' ? 'selected' : ''} onClick={() => setDraftConfig({ ...draftConfig, mode: 'api' })}>
              API
            </button>
          </div>

          {draftConfig.mode === 'demo' ? (
            <div className="presentation-box">
              <Sparkles size={22} />
              <div>
                <strong>Открыть обезличенный live-контур</strong>
                <span>Подходит для GitHub Pages и показа преподавателю без локального backend.</span>
              </div>
              <button type="button" className="primary-button" onClick={openPresentation}>
                Открыть демо
                <ChevronRight size={18} />
              </button>
            </div>
          ) : (
            <>
              <label>
                API URL
                <input
                  value={draftConfig.apiUrl}
                  onChange={(event) => setDraftConfig({ ...draftConfig, apiUrl: event.target.value })}
                  placeholder="https://example.edu/api"
                  autoComplete="url"
                  required
                />
              </label>
              <label>
                Email
                <input value={email} onChange={(event) => setEmail(event.target.value)} type="email" autoComplete="username" required />
              </label>
              <label>
                Пароль
                <input
                  value={password}
                  onChange={(event) => setPassword(event.target.value)}
                  type="password"
                  autoComplete="current-password"
                  minLength={12}
                  required
                />
              </label>
              {error && <div className="form-error">{error}</div>}
              <button className="primary-button" disabled={submitting}>
                <KeyRound size={18} />
                {submitting ? 'Входим...' : 'Войти'}
              </button>
            </>
          )}
        </form>
      </section>
    </main>
  );
}

function LiveView({
  stats,
  current,
  cameras,
  selectedRoom,
  onSelectRoom,
  telemetry,
  lastSnapshotAt,
  liveState,
}: {
  stats: DashboardStats;
  current: CurrentAttendance[];
  cameras: CameraType[];
  selectedRoom: CurrentAttendance | null;
  onSelectRoom: (roomId: number) => void;
  telemetry: TelemetryEvent[];
  lastSnapshotAt: Date | null;
  liveState: LiveState;
}) {
  const selectedCamera = selectedRoom ? cameras.find((camera) => camera.roomId === selectedRoom.roomId) : null;
  const visibleRooms = [...current].sort((left, right) => right.occupancyPercent - left.occupancyPercent);

  return (
    <div className="live-layout">
      <section className="live-stage-panel">
        <div className="section-heading">
          <div>
            <span className="eyebrow">Оперативный центр</span>
            <h1>Живая картина аудиторий</h1>
          </div>
          <div className="snapshot-chip">
            <Clock3 size={17} />
            {lastSnapshotAt ? formatClock(lastSnapshotAt) : 'ожидание'}
          </div>
        </div>
        <DemoScene />
        <div className="hero-metrics">
          <MetricCard icon={<Users size={20} />} label="Людей сейчас" value={stats.totalPeople} tone="blue" />
          <MetricCard icon={<DoorOpen size={20} />} label="Аудиторий" value={stats.rooms} tone="green" />
          <MetricCard icon={<Activity size={20} />} label="Средняя загрузка" value={`${stats.averageOccupancy}%`} tone="amber" />
          <MetricCard icon={<Wifi size={20} />} label="Камер онлайн" value={stats.onlineCameras} tone="red" />
        </div>
      </section>

      <aside className="focus-panel">
        <div className="focus-card">
          <div className="section-heading compact">
            <div>
              <span className="eyebrow">Выбранная аудитория</span>
              <h2>{selectedRoom?.roomName ?? 'Нет данных'}</h2>
            </div>
            <StatusPill state={liveState} />
          </div>
          {selectedRoom ? (
            <>
              <div className="big-count">
                <strong>{selectedRoom.count}</strong>
                <span>из {selectedRoom.capacity} мест</span>
              </div>
              <Progress value={selectedRoom.occupancyPercent} status={selectedRoom.status} />
              <dl className="detail-list">
                <div>
                  <dt>Корпус</dt>
                  <dd>{selectedRoom.building}, этаж {selectedRoom.floor}</dd>
                </div>
                <div>
                  <dt>Достоверность</dt>
                  <dd>{Math.round(selectedRoom.confidence * 100)}%</dd>
                </div>
                <div>
                  <dt>Источник</dt>
                  <dd>{selectedCamera ? `${selectedCamera.name} · ${streamLabel(selectedCamera.streamType)}` : 'камера не назначена'}</dd>
                </div>
              </dl>
            </>
          ) : (
            <EmptyState>Нет активных измерений.</EmptyState>
          )}
        </div>

        <div className="event-card">
          <div className="section-heading compact">
            <div>
              <span className="eyebrow">События</span>
              <h2>Лента состояния</h2>
            </div>
            <RadioTower size={19} />
          </div>
          <div className="event-feed">
            {telemetry.length === 0 ? (
              <EmptyState>Ожидание первого снимка.</EmptyState>
            ) : (
              telemetry.slice(0, 6).map((event) => (
                <article className={`event-item ${event.status}`} key={event.id}>
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
      </aside>

      <section className="room-strip">
        {visibleRooms.map((room) => (
          <button key={room.roomId} className={`room-tile ${room.status}`} onClick={() => onSelectRoom(room.roomId)}>
            <span>{room.roomName}</span>
            <strong>{room.occupancyPercent}%</strong>
            <Progress value={room.occupancyPercent} status={room.status} />
          </button>
        ))}
      </section>
    </div>
  );
}

function InsightsView({
  stats,
  rooms,
  current,
  timeline,
  selectedRoomId,
  onSelectRoom,
}: {
  stats: DashboardStats;
  rooms: Room[];
  current: CurrentAttendance[];
  timeline: TimelinePoint[];
  selectedRoomId: number | null;
  onSelectRoom: (roomId: number) => void;
}) {
  const chartData = timeline.map((point) => ({
    time: formatTime(point.bucket),
    avg: point.avgCount,
    peak: point.peakCount,
  }));

  return (
    <div className="insights-grid">
      <section className="chart-card">
        <div className="section-heading">
          <div>
            <span className="eyebrow">Аналитика</span>
            <h1>Динамика загрузки</h1>
          </div>
          <select value={selectedRoomId ?? ''} onChange={(event) => onSelectRoom(Number(event.target.value))} aria-label="Аудитория">
            {rooms.map((room) => (
              <option key={room.id} value={room.id}>
                {room.name}
              </option>
            ))}
          </select>
        </div>
        <div className="chart-wrap">
          <ResponsiveContainer width="100%" height="100%">
            <AreaChart data={chartData} margin={{ top: 8, right: 12, left: -18, bottom: 0 }}>
              <defs>
                <linearGradient id="peopleGradient" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="5%" stopColor="#2563eb" stopOpacity={0.28} />
                  <stop offset="95%" stopColor="#2563eb" stopOpacity={0.02} />
                </linearGradient>
              </defs>
              <CartesianGrid vertical={false} stroke="#dfe6ef" />
              <XAxis dataKey="time" tick={{ fontSize: 12 }} minTickGap={24} />
              <YAxis tick={{ fontSize: 12 }} />
              <Tooltip contentStyle={{ borderRadius: 14, border: '1px solid #dfe6ef' }} />
              <Area type="monotone" dataKey="avg" stroke="#2563eb" fill="url(#peopleGradient)" strokeWidth={2.5} />
              <Area type="monotone" dataKey="peak" stroke="#ef4444" fill="transparent" strokeWidth={2} />
            </AreaChart>
          </ResponsiveContainer>
        </div>
      </section>

      <section className="insight-stack">
        <MetricCard icon={<Layers3 size={20} />} label="Средняя загрузка" value={`${stats.averageOccupancy}%`} tone="blue" />
        <MetricCard icon={<Wrench size={20} />} label="Требуют внимания" value={stats.attentionRooms} tone="red" />
        <MetricCard icon={<Users size={20} />} label="Людей сейчас" value={stats.totalPeople} tone="green" />
      </section>

      <section className="density-grid">
        {current.map((room) => (
          <article className={`density-card ${room.status}`} key={room.roomId}>
            <div>
              <strong>{room.roomName}</strong>
              <span>{room.building}, этаж {room.floor}</span>
            </div>
            <b>{room.count}/{room.capacity}</b>
            <Progress value={room.occupancyPercent} status={room.status} />
          </article>
        ))}
      </section>
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
  const firstRoom = rooms[0]?.id ?? 1;
  const emptyForm = useMemo<CreateCameraPayload>(
    () => ({ roomId: firstRoom, name: '', sourceUrl: 'sample', streamType: 'sample', status: 'offline', enabled: true }),
    [firstRoom],
  );
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [form, setForm] = useState<CreateCameraPayload>(emptyForm);

  useEffect(() => {
    if (rooms.length > 0 && !rooms.some((room) => room.id === form.roomId)) {
      setForm((value) => ({ ...value, roomId: rooms[0].id }));
    }
  }, [form.roomId, rooms]);

  function selectCamera(camera: CameraType) {
    setSelectedId(camera.id);
    setForm({
      roomId: camera.roomId,
      name: camera.name,
      sourceUrl: camera.sourceUrl ?? '',
      streamType: camera.streamType,
      status: camera.status,
      enabled: camera.enabled,
    });
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (selectedId === null) {
      await onCreate(form);
      setForm({ ...emptyForm, roomId: form.roomId });
      return;
    }
    await onUpdate(selectedId, form);
  }

  return (
    <div className="source-layout">
      <section className="source-board">
        <div className="section-heading">
          <div>
            <span className="eyebrow">Источники</span>
            <h1>Камеры без магии</h1>
          </div>
          <button className="secondary-button" onClick={() => { setSelectedId(null); setForm(emptyForm); }}>
            <Plus size={17} />
            Новая
          </button>
        </div>

        <div className="source-presets">
          {sourcePresets.map((preset) => (
            <button
              key={preset.id}
              onClick={() => setForm((value) => ({ ...value, sourceUrl: preset.sourceUrl, streamType: preset.streamType }))}
            >
              <span>{preset.label}</span>
              <small>{preset.note}</small>
            </button>
          ))}
        </div>

        <div className="camera-grid">
          {cameras.map((camera) => (
            <button key={camera.id} className={`camera-card ${camera.status} ${camera.id === selectedId ? 'selected' : ''}`} onClick={() => selectCamera(camera)}>
              <div className="camera-card-top">
                <span className="camera-icon">{camera.status === 'online' ? <Wifi size={18} /> : <WifiOff size={18} />}</span>
                <StatusBadge status={camera.status} />
              </div>
              <strong>{camera.name}</strong>
              <span>{camera.roomName}</span>
              <code>{camera.sourceUrl ?? 'источник скрыт ролью'}</code>
              <small>{streamLabel(camera.streamType)}</small>
            </button>
          ))}
        </div>
      </section>

      {canManage && (
        <form className="edit-panel" onSubmit={submit}>
          <span className="eyebrow">{selectedId === null ? 'Новый источник' : 'Редактирование'}</span>
          <h2>{selectedId === null ? 'Подключить камеру' : 'Сохранить источник'}</h2>
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
            <input value={form.name} onChange={(event) => setForm({ ...form, name: event.target.value })} placeholder="Камера входной зоны" required />
          </label>
          <label>
            Источник
            <input value={form.sourceUrl} onChange={(event) => setForm({ ...form, sourceUrl: event.target.value })} placeholder="sample, device:0, rtsp://..." required />
          </label>
          <label>
            Тип
            <select value={form.streamType} onChange={(event) => setForm({ ...form, streamType: event.target.value as CameraType['streamType'] })}>
              <option value="sample">Sample video</option>
              <option value="device">Device</option>
              <option value="mjpeg">MJPEG/IP camera</option>
              <option value="rtsp">RTSP</option>
              <option value="http">HTTP</option>
              <option value="file">File</option>
              <option value="simulation">Simulation fallback</option>
            </select>
          </label>
          <label>
            Статус
            <select value={form.status} onChange={(event) => setForm({ ...form, status: event.target.value as CameraType['status'] })}>
              <option value="online">Онлайн</option>
              <option value="offline">Офлайн</option>
              <option value="maintenance">Сервис</option>
            </select>
          </label>
          <label className="check-row">
            <input type="checkbox" checked={form.enabled} onChange={(event) => setForm({ ...form, enabled: event.target.checked })} />
            Включена
          </label>
          <button className="primary-button">
            {selectedId === null ? <Plus size={18} /> : <CheckCircle2 size={18} />}
            {selectedId === null ? 'Добавить' : 'Сохранить'}
          </button>
        </form>
      )}
    </div>
  );
}

function RoomsView({
  rooms,
  current,
  canManage,
  onCreate,
}: {
  rooms: Room[];
  current: CurrentAttendance[];
  canManage: boolean;
  onCreate: (payload: CreateRoomPayload) => Promise<void>;
}) {
  const [form, setForm] = useState<CreateRoomPayload>({ name: '', building: 'Главный корпус', floor: '1', capacity: 30 });
  const currentByRoom = new Map(current.map((item) => [item.roomId, item]));

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    await onCreate(form);
    setForm({ name: '', building: 'Главный корпус', floor: '1', capacity: 30 });
  }

  return (
    <div className="rooms-layout">
      <section className="room-list-panel">
        <div className="section-heading">
          <div>
            <span className="eyebrow">Аудитории</span>
            <h1>Фонд помещений</h1>
          </div>
          <span className="count-chip">{rooms.length}</span>
        </div>
        <div className="room-directory">
          {rooms.map((room) => {
            const state = currentByRoom.get(room.id);
            return (
              <article className="directory-card" key={room.id}>
                <div>
                  <strong>{room.name}</strong>
                  <span>{room.building}, этаж {room.floor}</span>
                </div>
                <b>{state ? `${state.count}/${room.capacity}` : `0/${room.capacity}`}</b>
                <Progress value={state?.occupancyPercent ?? 0} status={state?.status ?? 'normal'} />
              </article>
            );
          })}
        </div>
      </section>

      {canManage && (
        <form className="edit-panel" onSubmit={submit}>
          <span className="eyebrow">Новая аудитория</span>
          <h2>Добавить помещение</h2>
          <label>
            Название
            <input value={form.name} onChange={(event) => setForm({ ...form, name: event.target.value })} required />
          </label>
          <label>
            Корпус
            <input value={form.building} onChange={(event) => setForm({ ...form, building: event.target.value })} required />
          </label>
          <label>
            Этаж
            <input value={form.floor} onChange={(event) => setForm({ ...form, floor: event.target.value })} required />
          </label>
          <label>
            Вместимость
            <input value={form.capacity} onChange={(event) => setForm({ ...form, capacity: Number(event.target.value) })} min={1} type="number" required />
          </label>
          <button className="primary-button">
            <Plus size={18} />
            Добавить
          </button>
        </form>
      )}
    </div>
  );
}

function MobileView() {
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const [cameraActive, setCameraActive] = useState(false);
  const [previewUrl, setPreviewUrl] = useState('http://localhost:8090');
  const [previewToken, setPreviewToken] = useState('');
  const [previewState, setPreviewState] = useState<PreviewState | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => () => stopCamera(), []);

  useEffect(() => {
    let cancelled = false;
    async function poll() {
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
    void poll();
    const interval = window.setInterval(() => void poll(), 1500);
    return () => {
      cancelled = true;
      window.clearInterval(interval);
    };
  }, [previewToken, previewUrl]);

  async function startCamera() {
    setError(null);
    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        video: { facingMode: { ideal: 'environment' }, width: { ideal: 1280 }, height: { ideal: 720 } },
        audio: false,
      });
      streamRef.current = stream;
      if (videoRef.current) {
        videoRef.current.srcObject = stream;
      }
      setCameraActive(true);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Нет доступа к камере');
    }
  }

  function stopCamera() {
    streamRef.current?.getTracks().forEach((track) => track.stop());
    streamRef.current = null;
    if (videoRef.current) {
      videoRef.current.srcObject = null;
    }
    setCameraActive(false);
  }

  const streamUrl = `${normalizePreviewUrl(previewUrl)}/v1/stream.mjpg${previewToken ? `?token=${encodeURIComponent(previewToken)}` : ''}`;

  return (
    <div className="mobile-workspace">
      <section className="mobile-intro">
        <div>
          <span className="eyebrow">Mobile companion</span>
          <h1>Телефон, планшет и preview worker в одном интерфейсе</h1>
          <p>Страница помогает показать мобильный сценарий без нативного приложения: проверка камеры устройства, просмотр MJPEG preview и инструкция для IP-camera режима.</p>
        </div>
        <Smartphone size={34} />
      </section>

      <section className="mobile-panels">
        <article className="phone-card">
          <div className="section-heading compact">
            <div>
              <span className="eyebrow">Device camera</span>
              <h2>Проверка камеры</h2>
            </div>
            <Laptop size={20} />
          </div>
          <video ref={videoRef} autoPlay playsInline muted className="device-video" />
          <div className="button-row">
            <button className="primary-button" onClick={() => void startCamera()} disabled={cameraActive}>
              <Camera size={18} />
              Включить
            </button>
            <button className="secondary-button" onClick={stopCamera}>
              <WifiOff size={18} />
              Стоп
            </button>
          </div>
        </article>

        <article className="preview-card">
          <div className="section-heading compact">
            <div>
              <span className="eyebrow">Worker preview</span>
              <h2>Live MJPEG</h2>
            </div>
            <RadioTower size={20} />
          </div>
          <div className="preview-frame">
            <img src={streamUrl} alt="AudienceFlow worker preview" />
          </div>
          <div className="inline-form">
            <label>
              Preview URL
              <input value={previewUrl} onChange={(event) => setPreviewUrl(event.target.value)} />
            </label>
            <label>
              Token
              <input value={previewToken} onChange={(event) => setPreviewToken(event.target.value)} type="password" />
            </label>
          </div>
          <div className="preview-kpis">
            <MetricCard icon={<Users size={18} />} label="Людей" value={previewState?.count ?? '-'} tone="blue" />
            <MetricCard icon={<ShieldCheck size={18} />} label="Confidence" value={previewState ? `${Math.round(previewState.confidence * 100)}%` : '-'} tone="green" />
            <MetricCard icon={<Activity size={18} />} label="FPS" value={previewState?.fps ?? '-'} tone="amber" />
          </div>
        </article>

        <article className="guide-card">
          <span className="eyebrow">Подключение телефона</span>
          <h2>Production путь без лишнего backend</h2>
          <ol>
            <li>Телефон и сервер в одной защищённой сети.</li>
            <li>На телефоне включается IP/MJPEG или RTSP camera app.</li>
            <li>В камерах указывается `phone:http://IP:PORT/video` или `rtsp://IP/live`.</li>
            <li>Worker запускается с этим `CAMERA_SOURCE`, а счёт уходит в Analytics API.</li>
          </ol>
          {error && <div className="form-error">{error}</div>}
        </article>
      </section>
    </div>
  );
}

function AccessView({ users, onCreate }: { users: UserView[]; onCreate: (payload: CreateUserPayload) => Promise<void> }) {
  const [form, setForm] = useState<CreateUserPayload>({ email: '', displayName: '', role: 'TEACHER', password: '' });

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    await onCreate(form);
    setForm({ email: '', displayName: '', role: 'TEACHER', password: '' });
  }

  return (
    <div className="access-layout">
      <section className="access-list">
        <div className="section-heading">
          <div>
            <span className="eyebrow">Доступ</span>
            <h1>Пользователи и роли</h1>
          </div>
          <ShieldCheck size={22} />
        </div>
        {users.length === 0 ? (
          <EmptyState>В презентационном режиме рабочие учётные записи не показываются.</EmptyState>
        ) : (
          users.map((user) => (
            <article className="user-row" key={user.id}>
              <UserRound size={18} />
              <div>
                <strong>{user.displayName}</strong>
                <span>{user.email}</span>
              </div>
              <RoleBadge role={user.role} />
            </article>
          ))
        )}
      </section>

      <form className="edit-panel" onSubmit={submit}>
        <span className="eyebrow">Новый пользователь</span>
        <h2>Создать доступ</h2>
        <label>
          Имя
          <input value={form.displayName} onChange={(event) => setForm({ ...form, displayName: event.target.value })} required />
        </label>
        <label>
          Email
          <input value={form.email} onChange={(event) => setForm({ ...form, email: event.target.value })} type="email" required />
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
          <input value={form.password} onChange={(event) => setForm({ ...form, password: event.target.value })} minLength={14} autoComplete="new-password" required />
        </label>
        <button className="primary-button">
          <Plus size={18} />
          Создать
        </button>
      </form>
    </div>
  );
}

function DemoScene({ compact = false }: { compact?: boolean }) {
  const people = Array.from({ length: compact ? 9 : 16 }, (_, index) => ({
    id: index + 1,
    x: 8 + (index % 4) * 23 + (index % 2) * 2,
    y: 16 + Math.floor(index / 4) * 18 + (index % 3) * 2,
  }));

  return (
    <div className={compact ? 'demo-scene compact' : 'demo-scene'}>
      <div className="scene-toolbar">
        <span>Camera A-305</span>
        <b>LIVE</b>
      </div>
      <div className="scene-grid">
        {people.map((person) => (
          <span key={person.id} className="person-dot" style={{ left: `${person.x}%`, top: `${person.y}%` }}>
            <i />
          </span>
        ))}
      </div>
      <div className="scene-overlay">
        <strong>{compact ? 18 : 42}</strong>
        <span>people detected</span>
      </div>
    </div>
  );
}

function NavButton({ active, icon, label, onClick }: { active: boolean; icon: ReactNode; label: string; onClick: () => void }) {
  return (
    <button className={active ? 'nav-button active' : 'nav-button'} onClick={onClick}>
      {icon}
      <span>{label}</span>
    </button>
  );
}

function MetricCard({ icon, label, value, tone }: { icon: ReactNode; label: string; value: ReactNode; tone: string }) {
  return (
    <article className={`metric-card ${tone}`}>
      <div className="metric-icon">{icon}</div>
      <div>
        <span>{label}</span>
        <strong>{value}</strong>
      </div>
    </article>
  );
}

function StatusPill({ state }: { state: LiveState }) {
  const labels: Record<LiveState, string> = {
    live: 'Live WebSocket',
    polling: 'Polling',
    presentation: 'Demo live',
    offline: 'Нет сигнала',
  };
  return (
    <span className={`status-pill ${state}`}>
      <RadioTower size={15} />
      {labels[state]}
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

function RoleBadge({ role }: { role: Role }) {
  return (
    <span className={`role-badge ${role.toLowerCase()}`}>
      <ShieldCheck size={14} />
      {roleLabels[role]}
    </span>
  );
}

function Progress({ value, status }: { value: number; status: CurrentAttendance['status'] }) {
  return (
    <div className="progress-track">
      <span className={`progress-fill ${status}`} style={{ width: `${Math.max(3, Math.min(100, value))}%` }} />
    </div>
  );
}

function EmptyState({ children }: { children: ReactNode }) {
  return (
    <div className="empty-state">
      <Eye size={18} />
      <span>{children}</span>
    </div>
  );
}

function buildStats(current: CurrentAttendance[], cameras: CameraType[]): DashboardStats {
  const averageOccupancy =
    current.length === 0 ? 0 : Math.round(current.reduce((sum, item) => sum + item.occupancyPercent, 0) / current.length);
  return {
    rooms: current.length,
    totalPeople: current.reduce((sum, item) => sum + item.count, 0),
    averageOccupancy,
    attentionRooms: current.filter((item) => item.status !== 'normal').length,
    onlineCameras: cameras.filter((camera) => camera.status === 'online').length,
  };
}

function buildTelemetry(items: CurrentAttendance[], receivedAt: Date, previous: TelemetryEvent[]): TelemetryEvent[] {
  if (items.length === 0) {
    return previous;
  }
  const sorted = [...items].sort((left, right) => right.occupancyPercent - left.occupancyPercent);
  const source = sorted.filter((item) => item.status !== 'normal').slice(0, 3);
  const selected = source.length > 0 ? source : sorted.slice(0, 2);
  const next = selected.map((item) => ({
    id: `${receivedAt.getTime()}-${item.roomId}`,
    ts: receivedAt,
    title: item.status === 'full' ? 'Критическая загрузка' : item.status === 'warning' ? 'Высокая загрузка' : 'Новый снимок',
    detail: `${item.roomName}: ${item.count}/${item.capacity}, ${item.occupancyPercent}%`,
    status: item.status === 'normal' ? ('info' as const) : item.status,
  }));
  return [...next, ...previous].slice(0, 32);
}

function streamLabel(streamType: CameraType['streamType']): string {
  const labels: Record<CameraType['streamType'], string> = {
    rtsp: 'RTSP',
    http: 'HTTP',
    mjpeg: 'MJPEG/IP',
    device: 'Device',
    file: 'File',
    sample: 'Sample video',
    simulation: 'Simulation fallback',
  };
  return labels[streamType];
}

function formatTime(value: string): string {
  return new Intl.DateTimeFormat('ru-RU', { hour: '2-digit', minute: '2-digit' }).format(new Date(value));
}

function formatClock(value: Date): string {
  return new Intl.DateTimeFormat('ru-RU', { hour: '2-digit', minute: '2-digit', second: '2-digit' }).format(value);
}
