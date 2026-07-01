# AudienceFlow

Distributed attendance-counting system for production practice.

AudienceFlow combines a Python camera worker, Go ingest gateway, Java Analytics API, PostgreSQL, and a TypeScript/React dashboard. The system supports role-based access for teachers, technicians, and administrators, generated local credentials, camera management, live occupancy, and 5-minute attendance aggregates.

## Quick start

```bash
./scripts/bootstrap-env.sh
docker compose up --build
```

Open http://localhost:3000 and sign in with the generated credentials printed by the bootstrap script.

To run a simulated camera worker:

```bash
docker compose --profile worker up --build vision-worker
```

## Services

| Service | Language | Purpose |
| --- | --- | --- |
| `services/vision-worker` | Python | Reads a device/RTSP/HTTP camera, detects people, stabilizes count, sends events. |
| `services/ingest-gateway` | Go | Accepts worker events, validates ingest key, applies backpressure, writes PostgreSQL batches. |
| `services/analytics-api` | Java / Spring Boot | Authentication, roles, rooms, cameras, aggregates, live WebSocket data. |
| `services/web` | TypeScript / React | Role-specific dashboard for teachers, technicians, and admins. |
| `infra/postgres` | SQL | Schema, indexes, sample rooms, aggregate view. |

## Security defaults

The repository does not contain working credentials. Run `./scripts/bootstrap-env.sh` to generate:

- PostgreSQL password
- ingest API key
- JWT signing secret
- initial admin, technician, and teacher passwords

The default `admin/admin` pattern is not used anywhere.

## Documentation

- [Architecture](docs/architecture.md)
- [Deployment](docs/deployment.md)
