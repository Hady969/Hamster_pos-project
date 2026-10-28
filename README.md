# VIDO POS Take-Home — Order & Inventory Microservices

Small order/inventory system demonstrating service boundaries, idempotency, concurrency control, and recovery under partial failures.

## Quick start

```bash
docker compose up --build
```

| Service | URL |
|---|---|
| Order Service | http://localhost:8080 |
| Inventory Service | http://localhost:8081 |
| PostgreSQL | localhost:5432 (`inventory_db`, `order_db`) |

Health:

```bash
curl http://localhost:8080/actuator/health
curl http://localhost:8081/actuator/health
```

Example happy path:

```bash
# Git Bash / WSL / macOS / Linux
bash examples/curl-demo.sh
```

```powershell
# Windows PowerShell
.\examples\curl-demo.ps1
```

Or open `examples/requests.http` in IntelliJ / VS Code REST Client.

## Run tests

Tests use Testcontainers (Docker required) and Java 21.

```bash
# Inventory (concurrency, all-or-nothing reserve, idempotent reserve)
docker run --rm -v "${PWD}/inventory-service:/app" -v /var/run/docker.sock:/var/run/docker.sock \
  -w /app maven:3.9.9-eclipse-temurin-21 mvn test

# Order (idempotency, rejection, unavailable inventory, lost-response reconcile)
docker run --rm -v "${PWD}/order-service:/app" -v /var/run/docker.sock:/var/run/docker.sock \
  -w /app maven:3.9.9-eclipse-temurin-21 mvn test
```

On Windows PowerShell, use:

```powershell
docker run --rm -v "${PWD}/inventory-service:/app" -v /var/run/docker.sock:/var/run/docker.sock -w /app maven:3.9.9-eclipse-temurin-21 mvn test
docker run --rm -v "${PWD}/order-service:/app" -v /var/run/docker.sock:/var/run/docker.sock -w /app maven:3.9.9-eclipse-temurin-21 mvn test
```

## Architecture

Two independently runnable Spring Boot services. Each owns its schema. They communicate over HTTP only (no shared DB access).

```text
Client
  |  POST /api/orders (+ Idempotency-Key)
  v
Order Service  ----HTTP reserve---->  Inventory Service
  | DB: order_db                         | DB: inventory_db
  | orders, order_items                  | products, reservations
  |
  +-- scheduled reconciler for PENDING orders
```

### Communication flow (place order)

1. Client sends `POST /api/orders` with `Idempotency-Key`.
2. Order Service stores a `PENDING` order (unique on idempotency key + request hash).
3. Order Service calls Inventory `POST /api/reservations` with `orderId` and items.
4. Inventory locks products in stable id order (`SELECT … FOR UPDATE`), checks stock, reserves **all or none**, persists reservation keyed by `orderId`.
5. On success, Order Service stores authoritative purchase prices/total and marks `CONFIRMED`.
6. On insufficient stock / unknown product, Order Service marks `REJECTED`.
7. On timeout/5xx, order stays `PENDING` (never false-confirmed). Reconciler later GETs reservation by `orderId` or retries reserve (idempotent).

```text
PENDING ──reserve ok──► CONFIRMED
   │
   ├──insufficient / not found──► REJECTED
   │
   └──transient failure──► PENDING ──reconcile──► CONFIRMED | REJECTED | PENDING
```

## Data ownership

| Data | Owner |
|---|---|
| Products, current price, available qty, reservations | Inventory |
| Orders, line items, purchase prices, totals, status, idempotency | Order |

Purchase prices are copied from Inventory at reservation time and frozen on the order.

## Idempotency

**Orders (`Idempotency-Key`)**

- Same key + same canonical request hash → return existing order.
- Same key + different body → `409 Conflict`.
- Unique DB constraint + race handling prevents duplicate orders under concurrency.

**Reservations (`orderId`)**

- Unique `reservations.order_id`.
- Repeating reserve for the same order returns the existing reservation and does **not** decrease stock again.
- Covers lost HTTP responses, client retries, and reconciler retries.

## Concurrency / overselling

Inventory loads all requested products with pessimistic write locks in sorted id order (avoids deadlocks), then validates quantities before decrementing. Two competitors for the last unit: at most one reservation succeeds.

## Recovery strategy

**Approach:** synchronous reservation + durable `PENDING` + periodic reconciliation.

| Scenario | Behavior |
|---|---|
| Two orders, last unit | DB locks → one `CONFIRMED`, one `REJECTED` |
| One line lacks stock | Transaction rolls back → no partial reservation; order `REJECTED` |
| Inventory down / timeout | Order stays `PENDING`; no optimistic confirm |
| Reserve succeeds, response lost | Inventory already has reservation; retry/reconcile is idempotent; order becomes `CONFIRMED` |
| Order crashes after reserve, before confirm | `PENDING` row remains; reconciler finds reservation → `CONFIRMED` |
| Duplicate request/delivery | Idempotency key / `orderId` uniqueness |

**Guarantees**

- No false `CONFIRMED` without a successful reservation outcome (or discovered existing `RESERVED`).
- At-most-once stock decrement per `orderId`.
- Eventually reaches `CONFIRMED` or `REJECTED` once Inventory is reachable (assuming stock still unavailable ⇒ reject on retry).

**Assumptions / limitations**

- Sync HTTP (no broker). If using messaging later, prefer transactional outbox so DB commit and event publish cannot diverge.
- Reconciler is polling-based (seconds of lag), not instant.
- Reserved stock is not auto-expired/released if an order stays `PENDING` forever while Inventory is up but Order is wiped (ops concern; not modeled).
- No auth, gateway, or Kubernetes (out of scope).

## Reproduce failure scenarios

### 1) Last unit race

```bash
# Create product with qty=1, then fire two orders concurrently with different Idempotency-Keys.
# Expect one CONFIRMED, one REJECTED; availableQuantity=0.
```

Covered by `ReservationServiceConcurrencyIT.concurrentOrdersForLastUnit_onlyOneSucceeds`.

### 2) Partial stock in multi-item order

Covered by `ReservationServiceConcurrencyIT.insufficientStockOnOneItem_rejectsWithoutPartialReservation`.

### 3) Inventory temporarily unavailable

Stop Inventory while placing an order, or use the unit/IT that simulates timeouts:

```bash
docker compose stop inventory-service
# Place order → status PENDING
docker compose start inventory-service
# Wait for reconciler (~5s+) → CONFIRMED or REJECTED
```

Also covered by `OrderServiceIT.inventoryUnavailable_leavesPendingWithoutFalseConfirmation`.

### 4) Lost reserve response / crash after reserve

Covered by `OrderServiceIT.lostResponseThenReconcile_confirmsWithoutDoubleReserve` and `crashAfterReserve_reconcileRecovers`.

### 5) Duplicate delivery

Replay the same `Idempotency-Key` + body (`OrderServiceIT.idempotentReplay_returnsSameOrder`) or repeat reserve (`duplicateReserveForSameOrder_doesNotDoubleReserve`).

## APIs (summary)

**Inventory**

- `POST /api/products` — create product
- `GET /api/products/{id}`
- `POST /api/reservations` — reserve by `orderId` (idempotent)
- `GET /api/reservations/by-order/{orderId}`
- `POST /api/reservations/by-order/{orderId}/release`

**Order**

- `POST /api/orders` — requires `Idempotency-Key`
- `GET /api/orders/{id}`

Correlation: send `X-Correlation-Id`; it is logged and forwarded Order → Inventory.

## Tradeoffs & unfinished work

- Chose sync HTTP + reconciler over a message broker to keep the 8h scope focused on correctness.
- No reservation TTL / compensation saga beyond reject-on-insufficient.
- No end-to-end Testcontainers compose test (services tested in isolation with MockWebServer / real Postgres).
- Local JDK on this machine was 17; images build/run on **Java 21**.

## Time spent

Approx. **7–8 hours** (design, implementation, tests, Docker, README).

## AI assistance

Cursor AI assisted with scaffolding, boilerplate, and drafting tests/docs. All business rules (locking, idempotency, reconcile paths) were reviewed manually against the assignment scenarios; tests were executed via Docker/Maven where possible to verify behavior.
