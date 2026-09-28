# 🪣 RateLimiter: A Token Bucket API Gateway with Spring Cloud Gateway & Redis

> A rate-limiting reverse proxy built with **Spring Boot 3**, **Spring Cloud Gateway**, and **Redis**. Every client gets its own *token bucket*. Requests that find a token are forwarded to the backend; requests that don't are rejected with **HTTP 429 Too Many Requests**.

This README is written as both a **project guide** and a **learning guide**. If you have never built a rate limiter, an API gateway, or used Redis, you can follow it from top to bottom, build the project, test it, and understand *why* each piece exists.

---

## 📑 Table of Contents

1. [What is this project?](#1--what-is-this-project)
2. [Concepts you need first](#2--concepts-you-need-first)
3. [Architecture](#3--architecture)
4. [Tech stack](#4--tech-stack)
5. [Project structure](#5--project-structure)
6. [How a request is processed](#6--how-a-request-is-processed)
7. [The token bucket algorithm in detail](#7--the-token-bucket-algorithm-in-detail)
8. [Configuration](#8--configuration)
9. [Getting started (step by step)](#9--getting-started-step-by-step)
10. [Testing guide](#10--testing-guide)
11. [Troubleshooting](#11--troubleshooting)
12. [Design decisions & lessons learned](#12--design-decisions--lessons-learned)
13. [Known limitations](#13--known-limitations)
14. [Roadmap / improvements](#14--roadmap--improvements)
15. [Glossary](#15--glossary)

---

## 1. 🎯 What is this project?

Imagine a nightclub with a bouncer. Everyone can *try* to get in, but the bouncer decides who enters and who is turned away when the club is too busy. This project is that bouncer for an API.

- Clients send requests to the **gateway** (`:8080`), never directly to the backend.
- The gateway identifies the client, checks that client's **token bucket** stored in **Redis**, and then either:
  - ✅ **forwards** the request to the backend API (`:8081`), or
  - 🚫 **rejects** it with `429 Too Many Requests`.

**Why rate limit at all?**

| Reason | Explanation |
|---|---|
| Protect the backend | One misbehaving client can't overload your service |
| Fairness | Every client gets a similar share of capacity |
| Abuse prevention | Slows brute-force, scraping, and spam |
| Cost control | Limits load on paid or expensive downstream resources |

> The backend is a **separate application**. This project only acts as the reverse proxy in front of it.

---

## 2. 🧠 Concepts you need first

### 2.1 What is an API Gateway / Reverse Proxy?

A single entry point in front of one or more services. It receives every request first and decides to **forward**, **modify**, or **reject** it. Common jobs: routing, authentication, logging, and **rate limiting** (this project).

### 2.2 Spring Cloud Gateway vocabulary

| Term | Meaning | Example |
|---|---|---|
| **Route** | A rule that sends matching requests to a destination | `/api/**` → `http://localhost:8081` |
| **Predicate** | The condition that decides if a route matches | path, header, HTTP method |
| **Filter** | An action applied to a matching request or response | strip a prefix, add a header, rate-limit |

### 2.3 Servlet stack vs Reactive stack

Spring Cloud Gateway runs on **Spring WebFlux (reactive)**, not Spring MVC. This is a major reason why the project must **not** include `spring-boot-starter-web`.

| Feature | Servlet stack (Spring MVC) | Reactive stack (Spring WebFlux) |
|---|---|---|
| Concurrency model | Thread-per-request | Event loop: a few threads handle many requests |
| I/O style | Blocking: threads wait for DB/API/file | Non-blocking: threads trigger work and move on |
| Server | Tomcat / Jetty | Netty |
| Resource use under load | Higher memory and CPU (many idle threads) | Very efficient, small footprint |

⚠️ Putting both `starter-web` (Tomcat) and the gateway (Netty) on the classpath causes a startup conflict.

### 2.4 What is a Token Bucket?

Picture a bucket that holds **tokens**:

- The bucket has a maximum **capacity** (here: `10`).
- Tokens are **added at a constant refill rate** (here: `5` per second) until the bucket is full.
- Each request must **take 1 token**.
- Empty bucket → the request is **rejected** (429).

This allows short **bursts** (a full bucket = 10 quick requests) while enforcing a **sustained average rate** (5 requests/second long-term).

### 2.5 Why Redis?

The bucket must remember two facts per client: **how many tokens are left** and **when it last refilled**. Where can that live?

| Option | Problem / Benefit |
|---|---|
| In-memory `HashMap` in the app | ❌ Works for only one instance, lost on restart, breaks in a cluster |
| **Redis** | ✅ Shared by all gateway instances, survives restarts, very fast (in-memory) |

### 2.6 What is a "Starter"?

A Spring Boot *starter* is a curated bundle of compatible libraries. Add one dependency and it pulls in the matching set, so you never fight version conflicts. (`spring-cloud-starter-gateway` is roughly 20 jars in a trench coat.)

---

## 3. 🏗️ Architecture

```
Client
  |
  | HTTP request to :8080/api/...
  v
Spring Cloud Gateway (:8080)
  |-- checks client token bucket in Redis (:6379)
  |-- allowed?  -> forwards to backend (:8081)
  |-- no tokens? -> returns 429
  v
Backend API (:8081)
```

**Port map (memorize this, every test depends on it):**

| Port | Component | Role |
|---|---|---|
| `6379` | Redis | State: who has how many tokens |
| `8081` | Backend API | Serves the real data (e.g. `/question/allQuestions`) |
| `8080` | Gateway | Decides allow/block and forwards traffic |

**Division of labor**

```
Spring Cloud Gateway  → receives and routes requests, applies the filter
Redis                 → stores bucket state (tokens + last refill time)
Your code             → the client-identification + token bucket logic
```

---

## 4. 🛠️ Tech stack

| Technology | Purpose |
|---|---|
| **Java 21** | Language/runtime (Java 17+ is the minimum for Spring Boot 3) |
| **Spring Boot 3.5.0** | Application framework |
| **Spring Cloud Gateway (2025.0.0 release train)** | Reverse proxy, routes, filters (runs on WebFlux/Netty) |
| **Redis** | Stores each client's bucket state |
| **Jedis** (with connection pool) | Java client used to talk to Redis |
| **Lombok** | Reduces boilerplate (getters, constructors, etc.) |
| **Maven (`mvnw`)** | Build tool via the bundled Maven Wrapper |
| **Docker** | Easiest way to run Redis locally |
| **curl.exe** | Sending test requests |

### Build-level pieces worth understanding

| Piece | What it does |
|---|---|
| `spring-boot-starter-parent` | The pom's "superclass": pre-declares versions of ~200 artifacts, sets Java level and encoding. That's why dependencies have no `<version>` tags. |
| `spring-cloud-dependencies` (BOM) | A *version catalog* with zero code. Guarantees the Spring Cloud version matches your Boot version. |
| `spring-boot-maven-plugin` | Builds a runnable "fat jar" and enables `mvn spring-boot:run`. |

---

## 5. 📁 Project structure

| File | Responsibility |
|---|---|
| `RateLimiterApplication.java` | Starts the Spring Boot application |
| `GatewayConfig.java` | Defines the `/api/**` route, strips the `/api` prefix, applies the custom filter |
| `TokenBucketRateLimiterFilter.java` | Identifies the client, checks the limit, rejects excess requests, sets response headers |
| `RedisTokenBucketService.java` | Reads/updates token counts and refill timestamps in Redis (the algorithm lives here) |
| `RateLimiterService.java` / `RateLimiterServiceImpl.java` | Service interface, plus the implementation connecting the filter to the Redis service |
| `RedisProperties.java` | Configures the Redis connection pool (Jedis pool) |
| `StatusController.java` | Gateway health and rate-limit status endpoints |
| `src/main/resources/application.properties` | All tunable configuration values |

**Layering idea:** *Filter → Service interface → Redis implementation.* The filter doesn't know how Redis works, so you can swap the storage or algorithm later without touching the filter.

---

## 6. 🔄 How a request is processed

1. A client sends a request, e.g. `http://localhost:8080/api/question/allQuestions`.
2. `GatewayConfig` matches the `/api/**` route and applies `TokenBucketRateLimiterFilter`.
3. The filter identifies the client from the **`X-Forwarded-For`** header if present, otherwise from the **remote address**.
4. `RateLimiterService` delegates the allowance check to `RedisTokenBucketService`.
5. Redis stores the **token count** and **last-refill timestamp** under keys tied to that client.
6. **If a token is available:** one is consumed and the request is forwarded. `stripPrefix(1)` removes `/api`, so `/api/question/allQuestions` reaches the backend as `/question/allQuestions`.
7. **If no token is available:** the gateway returns **429** without contacting the backend.
8. The filter adds **rate-limit headers** to the response (the configured limit and remaining tokens).

> 💡 **Why the `/api` prefix?** Testers must call `http://localhost:8080/api/...` through the gateway. The path `/question/...` only exists on the backend (`:8081`).

---

## 7. 🧮 The token bucket algorithm in detail

Redis stores **two values per client**:

```
tokens          → how many tokens are currently in the bucket
lastRefillTime  → timestamp of the last refill calculation
```

Together with `capacity` and `refillRate` from configuration, each request does this (conceptual pseudocode, not the exact source):

```
now      = current time
elapsed  = now - lastRefillTime                       (seconds)
tokens   = min(capacity, tokens + elapsed * refillRate)
lastRefillTime = now

if tokens >= 1:
    tokens = tokens - 1
    ALLOW  → forward request
else:
    REJECT → HTTP 429
```

```
        Request from client
                |
                v
     RedisTokenBucketService
                |
     +----------+-----------+
     v                      v
 Current tokens       Last refill time
     +----------+-----------+
                v
        Calculate refill
                v
          Allow / Reject
                v
              Redis
```

**Worked example** (`capacity=10`, `refill-rate=5/s`):

| Time | Event | Tokens after |
|---|---|---|
| t=0.0s | New client, full bucket | 10 |
| t=0.0s | 10 quick requests | 0 |
| t=0.0s | 11th request | 0 → **429** |
| t=1.0s | (1 second passes, +5 tokens) | 5 |
| t=1.0s | 1 request | 4 → allowed |

**Why "12 requests → 10 pass, 2 blocked" isn't always exact:** tokens refill while your loop runs. If each `curl` takes ~300 ms and the refill rate is 5/s, the bucket refills almost as fast as you drain it, so you may see *no* 429s at all. See [Testing](#10--testing-guide) for how to make the test deterministic.

---

## 8. ⚙️ Configuration

`src/main/resources/application.properties`:

```properties
server.port=8080

spring.data.redis.host=localhost
spring.data.redis.port=6379

rate-limiter.capacity=10
rate-limiter.refill-rate=5
rate-limiter.api-server-url=http://localhost:8081
rate-limiter.timeout=5000
```

| Property | Meaning |
|---|---|
| `server.port` | Port the gateway listens on |
| `spring.data.redis.host/port` | Where Redis is running |
| `rate-limiter.capacity` | Bucket size = maximum burst |
| `rate-limiter.refill-rate` | Tokens added per second (sustained rate). `0` freezes the bucket (useful for tests) |
| `rate-limiter.api-server-url` | Backend URL that allowed requests are forwarded to |
| `rate-limiter.timeout` | Timeout value in milliseconds |

> ⚠️ **Most common first-run mistake:** if `rate-limiter.api-server-url` points to `http://localhost:8080` (the gateway itself), the gateway forwards requests **to itself in a loop**. It must point to the backend: `http://localhost:8081`.

### How config reaches your code

Values in `application.properties` are mapped into a typed Java object with **`@ConfigurationProperties`** (instead of scattering `@Value` annotations).

- **Type-safe and structured:** one class represents the whole `rate-limiter.*` group.
- **No recompiling to change values:** edit the properties file and restart.
- **Dot-separated names** (`rate-limiter.capacity`) create a readable hierarchy.
- `@Value` works for one-off values, but reading many raw keys directly is error-prone (typos fail silently or at runtime).

---

## 9. 🚀 Getting started (step by step)

### 9.1 Prerequisites (one-time)

| Tool | Why | Minimum | Verify with |
|---|---|---|---|
| Java JDK | Runs the Spring Boot apps | 17 (project uses 21) | `java -version` |
| Redis | Stores token buckets | 7.x | `redis-cli ping` → `PONG` |
| Maven | Builds the project | not needed | use the bundled `mvnw` |
| curl | Sends test requests | any | pre-installed on Windows 10+ |
| PowerShell | Runs test loops | 5.1 works | `$PSVersionTable` |

**Redis options on Windows** (Windows has no official Redis build):

```powershell
# Option A: Docker (recommended)
docker run -d --name redis -p 6379:6379 redis:7

# Option B: WSL2
wsl sudo apt install redis-server && wsl sudo service redis-server start

# Option C: Memurai (Redis-compatible Windows service) → memurai.com
```

On Linux/macOS you can use the same Docker command or install Redis with your package manager.

**Why Docker first?** No system pollution, one command to start, and `docker stop redis` / `docker start redis` gives you controlled restarts.

**Why `mvnw` instead of installing Maven?** The Maven Wrapper ships inside the project and downloads the exact Maven version it was built with, so there is no version mismatch between machines.

> 🔎 Optional: **RedisInsight** (GUI) lets you watch bucket keys change live.

### 9.2 Clone

```bash
git clone https://github.com/Yuvraj-Pandiya/RateLimiter.git
cd RateLimiter
```

### 9.3 Check the configuration

Confirm `rate-limiter.api-server-url=http://localhost:8081` (see [Configuration](#8--configuration)).

### 9.4 Start the three processes, in dependency order

Start each one and confirm it is alive before starting the next. Starting the gateway first gives confusing connection errors that hide the real problem.

```powershell
# Terminal 1: REDIS (the gateway needs it on every request)
docker start redis          # first time: use the docker run command above

# Terminal 2: BACKEND (the gateway forwards to it)
cd <path-to-your-backend>
.\mvnw.cmd spring-boot:run

# Terminal 3: GATEWAY (last)
cd <path-to-RateLimiter>
.\mvnw.cmd spring-boot:run
```

On Linux/macOS use `./mvnw spring-boot:run`.

| Component | Success signal |
|---|---|
| Redis | `docker ps` lists it, or `redis-cli ping` → `PONG` |
| Backend | `Tomcat started on port 8081` (or similar startup message) |
| Gateway | `Netty started on port 8080` + `Started RateLimiterApplication` |

⏳ The **first** `mvnw` run downloads dependencies (2 to 5 minutes). It isn't frozen; watch the download log.

---

## 10. 🧪 Testing guide

Every test below uses the **real backend endpoint** `question/allQuestions`, because it is a genuine data route that passes through the whole chain (filter → Redis → forwarding → backend). Simple gateway-only endpoints like `/gateway/health` are answered by the gateway itself and **never touch the limiter or the backend**, so they can't prove the rate limiter works.

| Where you call it | URL | Goes through the limiter? |
|---|---|---|
| Directly on the backend | `http://localhost:8081/question/allQuestions` | ❌ No |
| Through the gateway | `http://localhost:8080/api/question/allQuestions` | ✅ Yes |

> **Golden rule:** never jump straight to the 12-request burst. If it fails you won't know which layer broke. Test **bottom-up**; each test proves exactly one thing.

> **PowerShell tip:** always write `& curl.exe`. Plain `curl` in PowerShell is an alias for `Invoke-WebRequest`, which behaves differently.

### Phase A: Smoke tests (isolate each layer)

**Test A: Is Redis alive?**
```powershell
redis-cli ping
```
Expected: `PONG`. If Redis is down, every gateway request fails later, and you'd waste time debugging the wrong thing.

**Test B: Does the real endpoint work on the backend alone? (bypass the gateway)**
```powershell
& curl.exe -i http://localhost:8081/question/allQuestions
```
Expected: `HTTP 200` and your JSON list of questions. This proves the forwarding target works and gives you the **baseline data** to compare against. If this fails, no gateway setting can fix it.

**Test C: Is the gateway process alive?**
```powershell
& curl.exe -i http://localhost:8080/gateway/health
```
This endpoint is handled by the gateway itself, so a pass only means the process is up and port 8080 is bound. It says nothing about limiting yet, which is why Test D matters more.

**Test D: Does the real endpoint work through the gateway? (the critical test)**
```powershell
& curl.exe -i -H "X-Forwarded-For: 203.0.113.10" "http://localhost:8080/api/question/allQuestions"
```
Expected: `HTTP 200`, the **same JSON as Test B**, plus the **rate-limit headers** (limit and remaining tokens) added by the filter. This one request exercises the entire chain: filter → Redis → token consumed → `/api` stripped → forwarded to `:8081/question/allQuestions` → response relayed. It is also where the `ClassCastException` import mistake shows up (see [Troubleshooting](#11--troubleshooting)).

**Test E: Did the limiter write state to Redis?**
```powershell
redis-cli KEYS *
redis-cli GET "<key-from-KEYS>"
```
You should see a key tied to `203.0.113.10` (the exact name depends on the implementation). This proves Test D really consumed a token and state was saved, instead of the limiter silently doing nothing.

**Test F: Baseline bucket status**
```powershell
& curl.exe -i -H "X-Forwarded-For: 203.0.113.10" http://localhost:8080/gateway/rate-limit/status
```
Know the token count *before* the burst. If it's already low, the burst results will be uninterpretable, so reset first.

### Phase B: The burst test on `question/allQuestions` (deterministic)

A rate limiter's result depends on **when requests arrive** and **what state Redis already holds**. So control both variables.

**Step 1: Reset state.** Redis persists across gateway restarts, so a stale empty bucket makes request #1 return 429.
```powershell
# Way 1: use a never-used client ID (brand-new full bucket) → e.g. 203.0.113.77
# Way 2: delete the key
redis-cli DEL "<key-name>"
```

**Step 2: Freeze refill (temporarily).**
```properties
rate-limiter.refill-rate=0
```
Restart the gateway. With `0`, time is removed from the experiment. (At `5/s`, a loop of ~300 ms requests refills the bucket about as fast as it drains, which can hide the 429s.)

**Step 3: Fire 12 requests at the real endpoint.**
```powershell
for ($i = 1; $i -le 12; $i++) {
    $code = & curl.exe -s -o NUL -w "%{http_code}" `
        -H "X-Forwarded-For: 203.0.113.77" `
        "http://localhost:8080/api/question/allQuestions"
    "Request ${i}: $code"
}
```

| Piece | Why |
|---|---|
| `& curl.exe` | Forces real curl, not the PowerShell alias |
| `-o NUL` | Discards the JSON body; we only want the status |
| `-w "%{http_code}"` | Prints only the HTTP status |
| Fixed `X-Forwarded-For` | Forces all requests into one bucket (locally everything is `127.0.0.1`) |
| `/api/question/allQuestions` | The real route: `/api` is stripped, backend receives `/question/allQuestions` |
| 12 requests | Capacity 10 → see both the last allowed and the first blocked |

**Expected output (`refill-rate=0`, fresh client):**
```
Request 1..10 : 200   ← token consumed, backend returned the questions
Request 11    : 429   ← bucket empty, blocked by the gateway
Request 12    : 429
```

> The backend status may be something other than `200`. What matters is that **excess requests are rejected by the gateway with `429`** before reaching the backend.

**Step 4: See a rejected response in full.**
```powershell
& curl.exe -i -H "X-Forwarded-For: 203.0.113.77" "http://localhost:8080/api/question/allQuestions"
```
Expected: `HTTP/1.1 429 Too Many Requests` with **no question JSON**, proving the request never reached the backend.

**Step 5: Confirm block and recovery.**
```powershell
# Bucket should be empty
& curl.exe -s -H "X-Forwarded-For: 203.0.113.77" http://localhost:8080/gateway/rate-limit/status

# Restore refill-rate=5, restart the gateway, then poll and watch tokens climb back toward 10
& curl.exe -s -H "X-Forwarded-For: 203.0.113.77" http://localhost:8080/gateway/rate-limit/status

# After a couple of seconds, the real endpoint works again
& curl.exe -i -H "X-Forwarded-For: 203.0.113.77" "http://localhost:8080/api/question/allQuestions"
```
This proves both halves of the design: **enforcement** (0 tokens → 429) and **recovery** (refill over time → `200` again).

### Phase C: Parallel burst (PowerShell 7+)

```powershell
1..12 | ForEach-Object -Parallel {
    & curl.exe -s -o NUL -w "%{http_code}`n" `
        -H "X-Forwarded-For: 203.0.113.88" `
        "http://localhost:8080/api/question/allQuestions"
} -ThrottleLimit 12
```
Useful when you want to test without freezing the refill rate. It's also the kind of load that exposes the race condition described in [Known limitations](#13--known-limitations).

### What the outcomes mean

| Observation | Meaning |
|---|---|
| 10×`200` then `429` | Limiter works ✅ |
| All 12 succeed | Loop is slower than the refill rate, so freeze refill or use parallel requests |
| Request #1 already `429` | Stale bucket in Redis, so reset (Step 1) |
| Mixed `200`/`429` under sustained load | Throughput is clamped to the refill rate: **correct behavior**, not a bug |

---

## 11. 🩹 Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `ClassCastException ... MutatedServerHttpRequest cannot be cast to ServerHttpRequest` in the filter | Wrong import: the servlet `org.springframework.http.server.ServerHttpRequest` | Use `org.springframework.http.server.reactive.ServerHttpRequest` |
| Every request → 500 at the limiter | Redis not running | `redis-cli ping`; start Redis |
| Gateway up but forwarding fails | `api-server-url` points to `8080` (itself), or backend is down | Set `http://localhost:8081`; run Test B |
| Request #1 of the burst → 429 | Stale bucket from a previous session | `redis-cli DEL <key>` or use a fresh IP |
| All 12 → 200, no 429 | Loop slower than refill rate | `refill-rate=0` + restart, or parallel requests |
| `Port 8080/8081 already in use` | Old JVM still running | `netstat -ano \| findstr :8080` then `taskkill /PID <pid> /F` |
| curl prints a huge HTML error page | PowerShell alias, not real curl | Always use `& curl.exe` |
| `mvnw` "script execution disabled" | PowerShell execution policy | `powershell -ExecutionPolicy Bypass -File .\mvnw.cmd spring-boot:run` |
| Startup crash with two servers | `spring-boot-starter-web` (Tomcat) + Gateway (Netty) | Remove `spring-boot-starter-web` |
| Gateway dependency version errors | Missing Spring Cloud BOM | Add `spring-cloud-dependencies` matching your Boot version |

---

## 12. 📚 Design decisions & lessons learned

### 12.1 Two ways to build a rate limiter on Spring Cloud Gateway

| | **Built-in `RequestRateLimiter`** | **This project (custom filter)** |
|---|---|---|
| Algorithm | Ready-made token bucket in the gateway jar | You write it yourself |
| Redis client | Reactive Lettuce (`spring-boot-starter-data-redis-reactive`) | Jedis with a connection pool |
| Atomicity | Runs a **Lua script inside Redis**, so read-check-update is one indivisible step | Separate Redis operations (see limitations) |
| Config knobs | `replenishRate`, `burstCapacity`, `key-resolver` | `capacity`, `refill-rate`, custom client identification |
| Best for | Production use with minimal code | **Learning how a token bucket really works** |

This project deliberately implements the algorithm by hand so every step (client identification, refill math, storage, rejection) is visible and understandable. The built-in approach is described here because it's what you'd usually reach for in production.

**How the built-in limiter works (for reference).** It runs a Lua script atomically in Redis:
1. Read the token count and last-refill time for the key
2. Refill tokens based on elapsed time (`replenishRate` per second)
3. Enough tokens → subtract 1 and allow; empty → deny (HTTP 429)

Bucket keys look like `request_rate_limiter.{clientKey}`. A `KeyResolver` bean decides what counts as "one client" (IP, user ID, API key...).

### 12.2 Jedis vs Lettuce

| Feature | Lettuce (Spring Data Redis Reactive) | Jedis + JedisPool |
|---|---|---|
| Architecture | Asynchronous, non-blocking | Synchronous, blocking |
| Threading | Event-driven (Netty); connections shared across threads | Each thread leases its own connection from a pool |
| Reactive support | Native (`Mono`/`Flux`) | None |
| Scalability | Very high with few connections | Bounded by pool size (`maxTotal`) and thread switching |
| Best for | WebFlux, high-throughput reactive APIs | Traditional thread-per-request (MVC) apps |

**Honest trade-off:** this gateway is reactive (WebFlux/Netty), while Jedis is blocking. That's fine for learning and moderate traffic, but blocking calls on an event-loop thread can hurt throughput at scale. The natural upgrade is Lettuce or a reactive Redis template (see [Roadmap](#14--roadmap--improvements)).

**Connection pool concept (`JedisPoolConfig`):** opening a Redis connection per request is slow. A pool keeps a set of ready connections that requests borrow and return, capping the maximum used (`maxTotal`).

### 12.3 Why `@ConfigurationProperties`

See [Configuration](#8--configuration): typed, structured, and changeable without recompiling.

### 12.4 Why keep the bucket state in Redis, not memory

Shared by all gateway instances, survives restarts, and is fast. This is what makes the limiter **distributed** rather than per-instance.

### 12.5 Why a fixed `X-Forwarded-For` in tests

Locally every request comes from `127.0.0.1`, so all tests would share one bucket. Sending a chosen `X-Forwarded-For` lets you simulate different clients or force the same client on demand.

### 12.6 The one-line testing philosophy

> Test layers in isolation (Redis → backend → gateway health → full routing), control the two variables that make a rate limiter non-deterministic (**stale Redis state** and **refill speed**), and only then measure.

---

## 13. ⚠️ Known limitations

- **Client identity can be forged.** `X-Forwarded-For` is handy for local testing, but any client can fake it unless a **trusted proxy** sets or validates it. Do not rely on arbitrary client-supplied values in a public deployment.
- **Not fully atomic.** The Redis check and the token decrement are currently separate operations. Under heavy parallel traffic, concurrent requests can race and let through more than the limit. A Lua script (or Redis transaction) fixes this.
- **Blocking Redis client in a reactive gateway.** Jedis is blocking; see [12.2](#122-jedis-vs-lettuce).
- **Minimal automated tests.** The existing test class only verifies that the Spring context loads. It does not verify Redis behavior or the "10 allowed, next rejected" scenario.
- **Redis is a single point of failure.** If Redis is unreachable, requests currently fail (500). Decide on a policy: fail-open (allow) or fail-closed (block).

---

## 14. 🗺️ Roadmap / improvements

- [ ] Make the token-bucket update **atomic** with a Redis **Lua script**
- [ ] Switch to **Lettuce / reactive Redis** to keep the event loop non-blocking
- [ ] Add **automated tests** (Testcontainers + `WebTestClient`) asserting "first 10 → 200, rest → 429"
- [ ] Add **TTL/expiry** to bucket keys so idle clients don't accumulate forever
- [ ] Support **per-route or per-user limits**, and API-key-based identification
- [ ] Add a **Redis failure policy** (fail-open vs fail-closed)
- [ ] Add **Docker Compose** to start Redis + backend + gateway with one command
- [ ] Add metrics and dashboards (Actuator + Prometheus/Grafana)

---

## 15. 📖 Glossary

| Term | Meaning |
|---|---|
| **Rate limiting** | Restricting how many requests a client may make in a period |
| **Token bucket** | Algorithm where requests spend tokens that refill at a steady rate |
| **Burst** | A short spike of requests, allowed up to the bucket capacity |
| **Refill rate** | Tokens added per second, i.e. the sustained request rate |
| **HTTP 429** | "Too Many Requests", the standard rate-limit rejection status |
| **Reverse proxy** | A server that receives client requests and forwards them to backends |
| **WebFlux** | Spring's reactive, non-blocking web stack |
| **Netty** | Asynchronous network server used by the gateway |
| **Event loop** | A few threads handling many requests without blocking |
| **Lua script (Redis)** | A script Redis runs as a single indivisible step |
| **Race condition** | A bug where concurrent operations interleave and produce wrong results |
| **BOM** | "Bill of Materials": a version catalog for compatible dependencies |
| **Connection pool** | A reusable set of open connections shared between requests |

---

## 🤝 Contributing

Issues and pull requests are welcome. If you find a bug or improve the docs, open an issue or PR.

## 👤 Author

**Yuvraj**: [GitHub](https://github.com/Yuvraj-Pandiya)

---

⭐ If this project helped you learn something, consider giving the repo a star!
