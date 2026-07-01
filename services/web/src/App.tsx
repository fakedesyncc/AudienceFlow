import {
  Activity,
  Building2,
  Camera,
  DoorOpen,
  Gauge,
  KeyRound,
  LogOut,
  Plus,
  RefreshCw,
  ShieldCheck,
  UserRound,
  Users,
  Wifi,
  WifiOff,
  Wrench,
} from 'lucide-react';
import { FormEvent, ReactNode, useCallback, useEffect, useMemo, useState } from 'react';
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
  createRoom,
  createUser,
  demoAccounts,
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

type View = 'overview' | 'rooms' | 'cameras' | 'users';

const sessionStorageKey = 'audienceflow.session';
const roleLabels: Record<Role, string> = {
  ADMIN: 'Администратор',
  TECHNICIAN: 'Техник',
  TEACHER: 'Преподаватель',
};

export function App() {
  const [session, setSession] = useState<AuthSession | null>(() => restoreSession());
  const [runtimeConfig, setRuntimeConfig] = useState<RuntimeConfig>(() => loadRuntimeConfig());
  const [activeView, setActiveView] = useState<View>('overview');
  const [current, setCurrent] = useState<CurrentAttendance[]>([]);
  const [timeline, setTimeline] = useState<TimelinePoint[]>([]);
  const [rooms, setRooms] = useState<Room[]>([]);
  const [cameras, setCameras] = useState<CameraType[]>([]);
  const [users, setUsers] = useState<UserView[]>([]);
  const [selectedRoomId, setSelectedRoomId] = useState<number | null>(null);
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
      setCurrent(currentData);
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
    const interval = window.setInterval(() => void loadData(), 10_000);
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
    socket.onmessage = (event) => {
      const message = parseLiveMessage(event.data);
      setCurrent(message.rooms);
    };
    socket.onerror = () => setError('Live-подключение временно недоступно');
    return () => socket.close();
  }, [session]);

  const dashboardStats = useMemo(() => buildStats(current, cameras), [current, cameras]);

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
    { id: 'overview' as View, label: 'Обзор', icon: Activity, enabled: true },
    { id: 'rooms' as View, label: 'Аудитории', icon: DoorOpen, enabled: true },
    { id: 'cameras' as View, label: 'Камеры', icon: Camera, enabled: true },
    { id: 'users' as View, label: 'Пользователи', icon: Users, enabled: isAdmin },
  ].filter((item) => item.enabled);

  function handleLogout() {
    localStorage.removeItem(sessionStorageKey);
    setSession(null);
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

  async function handleCreateUser(payload: CreateUserPayload) {
    if (!session) {
      return;
    }
    const user = await createUser(session, payload);
    setUsers((items) => [...items, user]);
  }

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <div className="brand-mark">
            <Gauge size={22} />
          </div>
          <div>
            <strong>AudienceFlow</strong>
            <span>{session.demo ? 'GitHub Pages demo' : session.apiUrl}</span>
          </div>
        </div>

        <nav className="nav-list">
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

        <div className="sidebar-footer">
          <div className="profile-chip">
            <UserRound size={17} />
            <div>
              <strong>{session.user.displayName}</strong>
              <span>{roleLabels[session.user.role]}</span>
            </div>
          </div>
          <button className="icon-button wide" onClick={handleLogout} title="Выйти">
            <LogOut size={17} />
            <span>Выйти</span>
          </button>
        </div>
      </aside>

      <main className="workspace">
        <header className="topbar">
          <div>
            <h1>{viewTitle(activeView)}</h1>
            <p>{new Intl.DateTimeFormat('ru-RU', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date())}</p>
          </div>
          <div className="topbar-actions">
            <RoleBadge role={session.user.role} />
            <button className="icon-button" onClick={() => void loadData()} title="Обновить">
              <RefreshCw size={18} className={loading ? 'spin' : ''} />
            </button>
          </div>
        </header>

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
            stats={dashboardStats}
            selectedRoomId={selectedRoomId}
            onSelectRoom={setSelectedRoomId}
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
          />
        )}
        {activeView === 'users' && isAdmin && <UsersView users={users} onCreate={handleCreateUser} />}
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
  const [email, setEmail] = useState(config.mode === 'demo' ? demoAccounts[0].email : '');
  const [password, setPassword] = useState(config.mode === 'demo' ? demoAccounts[0].password : '');
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
      localStorage.setItem(sessionStorageKey, JSON.stringify(session));
      onLogin(session);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Не удалось войти');
    } finally {
      setSubmitting(false);
    }
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
            <span>{draftConfig.mode === 'demo' ? 'Демо-режим' : 'Подключение к API'}</span>
          </div>
        </div>

        <form onSubmit={submit} className="login-form">
          <div className="mode-switch" role="group" aria-label="Режим подключения">
            <button
              type="button"
              className={draftConfig.mode === 'demo' ? 'selected' : ''}
              onClick={() => {
                setDraftConfig({ ...draftConfig, mode: 'demo' });
                setEmail(demoAccounts[0].email);
                setPassword(demoAccounts[0].password);
              }}
            >
              Demo
            </button>
            <button
              type="button"
              className={draftConfig.mode === 'api' ? 'selected' : ''}
              onClick={() => setDraftConfig({ ...draftConfig, mode: 'api' })}
            >
              API
            </button>
          </div>

          {draftConfig.mode === 'api' && (
            <label>
              API URL
              <input
                value={draftConfig.apiUrl}
                onChange={(event) => setDraftConfig({ ...draftConfig, apiUrl: event.target.value })}
                placeholder="https://example.com/api"
                required
              />
            </label>
          )}

          <label>
            Email
            <input value={email} onChange={(event) => setEmail(event.target.value)} type="email" required />
          </label>
          <label>
            Пароль
            <input
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              type="password"
              minLength={12}
              required
            />
          </label>
          {error && <div className="form-error">{error}</div>}
          <button className="primary-button" disabled={submitting}>
            <KeyRound size={18} />
            <span>{submitting ? 'Вход...' : 'Войти'}</span>
          </button>
        </form>

        {draftConfig.mode === 'demo' && (
          <div className="demo-logins">
            {demoAccounts.map((account) => (
              <button
                key={account.role}
                type="button"
                onClick={() => {
                  setEmail(account.email);
                  setPassword(account.password);
                }}
              >
                {roleLabels[account.role]}
              </button>
            ))}
          </div>
        )}
      </section>
    </main>
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
}: {
  cameras: CameraType[];
  rooms: Room[];
  canManage: boolean;
  onCreate: (payload: CreateCameraPayload) => Promise<void>;
}) {
  const [form, setForm] = useState<CreateCameraPayload>({
    roomId: rooms[0]?.id ?? 1,
    name: '',
    sourceUrl: '',
    streamType: 'rtsp',
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
    await onCreate(form);
    setForm((value) => ({ ...value, name: '', sourceUrl: '', status: 'offline' }));
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
            <article className="camera-row" key={camera.id}>
              <div className="camera-icon">
                {camera.status === 'online' ? <Wifi size={18} /> : <WifiOff size={18} />}
              </div>
              <div>
                <strong>{camera.name}</strong>
                <span>{camera.roomName}</span>
                <code>{camera.sourceUrl ?? 'source hidden'}</code>
              </div>
              <StatusBadge status={camera.status} />
            </article>
          ))}
        </div>
      </section>

      {canManage && (
        <section className="panel form-panel">
          <div className="panel-header">
            <h2>Подключить камеру</h2>
            <Wrench size={20} />
          </div>
          <form onSubmit={submit} className="stacked-form">
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
                placeholder="rtsp://..."
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
                <option value="device">Device</option>
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
              <Plus size={18} />
              <span>Добавить</span>
            </button>
          </form>
        </section>
      )}
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
    password: generatePassword(),
  });

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    await onCreate(form);
    setForm({ email: '', displayName: '', role: 'TEACHER', password: generatePassword() });
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
                minLength={12}
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
  return <span className={`status-badge ${status}`}>{status}</span>;
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

function viewTitle(view: View): string {
  switch (view) {
    case 'rooms':
      return 'Аудитории';
    case 'cameras':
      return 'Камеры';
    case 'users':
      return 'Пользователи';
    default:
      return 'Обзор';
  }
}

function formatTime(value: string): string {
  return new Intl.DateTimeFormat('ru-RU', { hour: '2-digit', minute: '2-digit' }).format(new Date(value));
}

function restoreSession(): AuthSession | null {
  const raw = localStorage.getItem(sessionStorageKey);
  if (!raw) {
    return null;
  }
  try {
    const parsed = JSON.parse(raw) as AuthSession;
    if (!parsed.token || !parsed.user || (!parsed.demo && !parsed.apiUrl)) {
      localStorage.removeItem(sessionStorageKey);
      return null;
    }
    return parsed.demo ? { ...parsed, apiUrl: null } : parsed;
  } catch {
    localStorage.removeItem(sessionStorageKey);
    return null;
  }
}

function generatePassword(): string {
  const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%';
  const values = new Uint32Array(18);
  crypto.getRandomValues(values);
  return Array.from(values, (value) => alphabet[value % alphabet.length]).join('');
}
