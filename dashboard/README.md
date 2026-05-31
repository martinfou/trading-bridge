# Trading Bridge — Laravel Control Room (Story 13.7)

Thin dashboard client for the Java control plane. No trading logic in PHP.

## Setup

```bash
cd dashboard
cp .env.example .env   # if needed
composer install
php artisan key:generate
```

## Run

Terminal 1 — Java control plane:

```bash
mvn exec:java -pl trading-runtime \
  -Dexec.mainClass=com.martinfou.trading.runtime.ControlPlaneMain
```

Terminal 2 — Laravel dashboard:

```bash
cd dashboard
php artisan serve --port=8000
```

Open http://localhost:8000/control

## Config

| Variable | Default | Description |
|----------|---------|-------------|
| `CONTROL_PLANE_URL` | `http://127.0.0.1:8080` | Java REST API base URL |
| `CONTROL_ROOM_REFRESH_SECONDS` | `5` | Page auto-refresh interval |

## Tests

```bash
cd dashboard && php artisan test
```
