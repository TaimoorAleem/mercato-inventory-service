# Mercato — Inventory Service

Stock availability service for **Mercato**, a distributed e-commerce platform. Answers one question: *is there enough of this SKU in stock?*

Part of a multi-repository project — see the [API Gateway repository](https://github.com/TaimoorAleem/api-gateway) for the system overview and links to the other services.

---

## Role in the System

This is the only service in the platform that is called **both** from the edge and from another service:

```
Angular SPA ──▶ API Gateway ──┐
   :4200          :9000       │
                              ├──▶ inventory-service ──▶ MySQL
              order-service ──┘         :8082             :3306
                  :8081
```

The important path is the second one. When a client places an order, `order-service` calls `GET /api/inventory` **synchronously** before persisting anything — an out-of-stock response aborts the order with `409 Conflict`, and an unreachable inventory service aborts it with `503 Service Unavailable`.

That makes this service a hard dependency of the order flow, which is why `order-service` wraps the call in a Resilience4j circuit breaker with retries and a 3s time limit rather than calling it naively.

The service performs no authentication of its own — the gateway validates JWTs, and `order-service` calls it directly over the internal network.

---

## API

### `GET /api/inventory?skuCode={sku}&quantity={n}` → `200 OK`

Returns a bare JSON boolean: `true` if a row exists for `skuCode` with `quantity >= n`, otherwise `false`.

```bash
curl "http://localhost:8082/api/inventory?skuCode=iphone_15&quantity=5"
# true

curl "http://localhost:8082/api/inventory?skuCode=iphone_15&quantity=500"
# false

curl "http://localhost:8082/api/inventory?skuCode=does_not_exist&quantity=1"
# false
```

The check is a single derived query — `existsBySkuCodeAndQuantityIsGreaterThanEqual` — so it never loads the row, only tests for its existence.

> **Note:** both parameters are unannotated and therefore optional. A request with `quantity` omitted passes `null` into the repository query and fails at the JDBC layer rather than returning a `400`.

This endpoint is **read-only**. Placing an order does not decrement stock — there is no reservation, no decrement, and no write path of any kind in this service. Stock levels only change through Flyway migrations or direct database access.

---

## Data Model

Table `t_inventory` (created by `V1__init.sql`):

| Column | Type | Notes |
|---|---|---|
| `id` | `bigint` | auto-increment primary key |
| `sku_code` | `varchar(255)` | maps to `Inventory.skuCode` |
| `quantity` | `int` | available units |

Schema is managed entirely by **Flyway**; `spring.jpa.hibernate.ddl-auto` is set to `none`, so Hibernate never touches DDL.

`V2__add_inventory.sql` seeds four SKUs with 100 units each:

```
iphone_15 · pixel_8 · galaxy_26 · oneplus_12
```

These are the SKUs to use when creating products in `product-service` if you want an end-to-end orderable catalog.

---

## Getting Started

### Prerequisites

- Java 21
- Docker & Docker Compose
- Maven (or the bundled `./mvnw`)

### 1. Start MySQL

```bash
docker compose up -d
```

MySQL 8.3.0 listens on `3306` with root password `mysql`. The container runs `docker/mysql/init.sql` on first start, which creates the `inventory_service` database.

> The order service runs its own MySQL on `3307` specifically so both can run side by side.

### 2. Create `.env`

The application imports `.env` via `spring.config.import=optional:file:.env[.properties]`. There is no `.env.example` in this repository — create the file yourself:

```properties
DB_URL=jdbc:mysql://localhost:3306/inventory_service
DB_USERNAME=root
DB_PASSWORD=mysql
```

All three variables are required; the application will not start without them.

### 3. Run the service

```bash
./mvnw spring-boot:run
```

Flyway applies `V1` and `V2` on startup, so the table exists and is seeded before the first request. The service listens on `http://localhost:8082`.

### 4. Verify

```bash
curl "http://localhost:8082/api/inventory?skuCode=pixel_8&quantity=10"
# true
```

---

## Configuration

`src/main/resources/application.properties`:

| Property | Value |
|---|---|
| `server.port` | `8082` |
| `spring.datasource.url` | `${DB_URL}` |
| `spring.datasource.username` | `${DB_USERNAME}` |
| `spring.datasource.password` | `${DB_PASSWORD}` |
| `spring.jpa.hibernate.ddl-auto` | `none` (Flyway owns the schema) |

Unlike the other services, no actuator endpoints are exposed here.

---

## Testing

```bash
./mvnw test
```

`InventoryServiceApplicationTests` is a context-load test backed by `TestcontainersConfiguration`, which supplies a MySQL container via `@ServiceConnection`. It verifies the application starts and Flyway migrations apply cleanly, but does not exercise the endpoint.

`TestInventoryServiceApplication` lets you run the app locally against Testcontainers-managed MySQL instead of Docker Compose — useful when you'd rather not manage the compose stack:

```bash
./mvnw spring-boot:test-run
```

Docker must be running for either.

---

## Project Layout

```
src/main/java/com/taimoor/inventory_service/
├── InventoryServiceApplication.java
├── controller/InventoryController.java   # GET /api/inventory
├── service/InventoryService.java         # isInStock delegation
├── repository/InventoryRepository.java   # Derived existsBy… query
└── model/Inventory.java                  # @Entity → t_inventory
src/main/resources/db/migration/
├── V1__init.sql                          # Table DDL
└── V2__add_inventory.sql                 # Seed data
docker/mysql/init.sql                     # CREATE DATABASE
docker-compose.yml                        # MySQL 8.3.0 on :3306
```

## Tech Stack

- **Spring Boot 4.1.0** on Java 21
- **Spring Data JPA** + **MySQL 8**
- **Flyway** (with `flyway-mysql`)
- **Lombok**
- **Testcontainers** (MySQL)

---

## Known Limitations

- **Stock is never decremented.** Orders check availability but do not reserve or consume inventory, so the same 100 units can be sold indefinitely. There is no write API.
- Query parameters are not validated or marked required, so a malformed request surfaces as a `500` rather than a `400`.
- No `.env.example` is committed — required variables have to be inferred from `application.properties`.
- Single-SKU check only; there is no bulk/batch availability endpoint.
- No actuator/health endpoint exposed, so the gateway cannot health-check this service directly.

## License

Personal learning and portfolio project.
