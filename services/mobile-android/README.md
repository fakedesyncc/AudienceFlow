# AudienceFlow Mobile Android

Нативный Android-клиент AudienceFlow для демонстрации и полевого просмотра камер без Electron и PWA.

## Возможности

- настройка `API URL`, `Preview URL` и `Preview token` прямо на устройстве;
- вход через `/auth/login` без встроенных логинов и паролей;
- ручная вставка JWT, если токен выдан отдельно;
- просмотр текущей посещаемости через `/attendance/current`;
- MJPEG preview vision-worker через `/v1/stream.mjpg`;
- быстрый demo-профиль для Android Emulator (`10.0.2.2`);
- запуск системной камеры устройства через native camera intent.

## Безопасность

В приложении нет предустановленных учётных данных. Пароль не сохраняется. В `SharedPreferences` сохраняются только адреса сервисов и токены, которые пользователь ввёл сам или получил после входа.

Для реального стенда используй HTTPS для Analytics API и preview reverse-proxy. Cleartext HTTP включён только для университетской демонстрации, локального Docker и Android Emulator.

## Сборка APK

Требования:

- JDK 17+;
- Android SDK с platform `android-36`;
- Android SDK Build Tools `36.0.0`;
- Gradle 9.1+.

Команда:

```bash
gradle -p services/mobile-android :app:assembleDebug
```

APK появится здесь:

```text
services/mobile-android/app/build/outputs/apk/debug/app-debug.apk
```

## Настройка для локального Docker

Для Android Emulator:

```text
API URL:     http://10.0.2.2:8080/api
Preview URL: http://10.0.2.2:8090
```

Для физического телефона в одной Wi-Fi сети укажи IP компьютера:

```text
API URL:     http://192.168.1.10:8080/api
Preview URL: http://192.168.1.10:8090
```

Если у vision-worker задан `PREVIEW_TOKEN`, введи его в поле `Preview token`.
