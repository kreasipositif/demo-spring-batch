# Demo Spring Batch — Transaction Validation Monorepo

A multi-service **Nx monorepo** that simulates a high-throughput bank transaction validation pipeline using **Spring Batch 5**, **Java 21 virtual threads**, and **Resilience4j bulkheads**.

---

## Table of Contents

- [Architecture Overview](#architecture-overview)
- [Services](#services)
  - [config-service](#1-config-service-port-8081)
  - [account-validation-service](#2-account-validation-service-port-8082)
  - [batch-processor](#3-batch-processor-port-8083)
- [Prerequisites](#prerequisites)
- [Running the Simulation](#running-the-simulation)
  - [Step 1 — Generate the Input CSV](#step-1--generate-the-input-csv)
  - [Step 2 — Start All Three Services](#step-2--start-all-three-services)
  - [Step 3 — Trigger the Batch Job](#step-3--trigger-the-batch-job)
  - [Step 4 — Check the Job Status](#step-4--check-the-job-status)
  - [Step 5 — Inspect the Output Files](#step-5--inspect-the-output-files)
- [Checking Results](#checking-results)
  - [Via REST API](#via-rest-api)
  - [Via Output CSV Files](#via-output-csv-files)
  - [Via Actuator](#via-actuator)
  - [Via H2 Console](#via-h2-console)
- [Nx Commands Reference](#nx-commands-reference)
- [Project Structure](#project-structure)
- [Bulkhead Guide — SemaphoreBulkhead vs ThreadPoolBulkhead](#bulkhead-guide--semaphorebulkhead-vs-threadpoolbulkhead)
- [Key Design Decisions](#key-design-decisions)

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                         batch-processor :8083                        │
│                                                                       │
│  POST /api/v1/batch/start                                            │
│       │                                                               │
│       ▼                                                               │
│  transactionValidationJob                                             │
│       │                                                               │
│       ▼  RangePartitioner (splits CSV line range)                    │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │  Partition-0    Partition-1  ...  Partition-9                 │   │
│  │  (virtual thread each)                                        │   │
│  │                                                               │   │
│  │  FlatFileItemReader                                           │   │
│  │       │                                                       │   │
│  │       ▼  TransactionItemProcessor                             │   │
│  │       │   ├─ SemaphoreBulkhead ──► config-service :8081      │   │
│  │       │   │    validate bank codes + amount limits            │   │
│  │       │   └─ ThreadPoolBulkhead ─► account-validation :8082  │   │
│  │       │        validate source & beneficiary accounts         │   │
│  │       ▼                                                       │   │
│  │  TransactionItemWriter                                        │   │
│  │   ├─ valid-pN-<ts>.csv                                        │   │
│  │   └─ invalid-pN-<ts>.csv                                      │   │
│  └──────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Services

### 1. `config-service` (port 8081)

Provides static configuration for valid bank codes and transaction-type amount limits.

| Endpoint | Description |
|---|---|
| `GET /api/v1/config/bank-codes/{code}/validate` | Returns whether a bank code is valid |
| `GET /api/v1/config/transaction-limits/{type}/validate?amount=` | Returns whether an amount is within the allowed range for a transaction type |
| `GET /swagger-ui.html` | Interactive API docs |

**Valid bank codes:** `BCA`, `BNI`, `BRI`, `MANDIRI`, `CIMB`, `DANAMON`, `PERMATA`, `BTN`, `BSI`, `OCBC`

**Transaction type limits (IDR):**

| Type | Min | Max |
|---|---|---|
| `TRANSFER` | 10,000 | 1,000,000,000 |
| `PAYMENT` | 1,000 | 500,000,000 |
| `TOPUP` | 10,000 | 50,000,000 |
| `WITHDRAWAL` | 50,000 | 20,000,000 |

---

### 2. `account-validation-service` (port 8082)

A mock downstream service that validates bank account numbers. Adds a simulated **500 ms network latency** to mimic real-world conditions.

| Endpoint | Description |
|---|---|
| `POST /api/v1/accounts/validate` | Validate a single account |
| `POST /api/v1/accounts/validate/bulk` | Validate multiple accounts in one call |
| `GET /swagger-ui.html` | Interactive API docs |

**Seeded accounts:**

| Account No. | Name | Bank | Status |
|---|---|---|---|
| 1234567890 | Budi Santoso | BCA | ✅ ACTIVE |
| 0987654321 | Siti Rahayu | BNI | ✅ ACTIVE |
| 1122334455 | Ahmad Fauzi | BRI | ✅ ACTIVE |
| 5544332211 | Dewi Lestari | MANDIRI | ✅ ACTIVE |
| 6677889900 | Rudi Hermawan | CIMB | ❌ INACTIVE |
| 9900112233 | Rina Kusuma | DANAMON | ✅ ACTIVE |
| 3344556677 | Hendra Gunawan | PERMATA | ❌ BLOCKED |
| 7788990011 | Yuni Astuti | BTN | ✅ ACTIVE |
| 2233445566 | Fajar Nugroho | BSI | ✅ ACTIVE |
| 4455667788 | Indah Permata | OCBC | ✅ ACTIVE |
| 1357924680 | Wahyu Prasetyo | BCA | ✅ ACTIVE |
| 2468013579 | Maya Sari | BRI | ✅ ACTIVE |
| 1111222233 | Doni Kurniawan | MANDIRI | ✅ ACTIVE |
| 4444555566 | Lina Marlina | BNI | ❌ INACTIVE |
| 7777888899 | Agus Salim | BSI | ✅ ACTIVE |

Any account number **not in this list** will return `NOT_FOUND`.

---

### 3. `batch-processor` (port 8083)

The main service. Reads a large CSV file of transactions, validates each record against the two downstream services, and writes results to separate output CSV files.

**Key features:**
- **Partitioned step** — `RangePartitioner` splits the input CSV into 10 equal line ranges
- **Virtual threads** — each partition worker runs on its own Java 21 virtual thread via `VirtualThreadTaskExecutor`
- **SemaphoreBulkhead** — limits concurrent calls to `config-service` (max 20)
- **FixedThreadPoolBulkhead** — limits async calls to `account-validation-service` (core 10 / max 20 / queue 200)
- **Chunk-oriented processing** — default chunk size of 100 records per write cycle
- **Step-scoped writer** — each partition writes to its own pair of output files (`valid-pN-<ts>.csv` / `invalid-pN-<ts>.csv`)

| Endpoint | Description |
|---|---|
| `POST /api/v1/batch/start?inputFile=` | Trigger a job (optional `inputFile` path override) |
| `GET /api/v1/batch/status/{jobExecutionId}` | Get job status + per-partition step summaries |
| `GET /swagger-ui.html` | Interactive API docs |
| `GET /h2-console` | H2 in-memory DB console (Spring Batch metadata) |
| `GET /actuator/health` | Health check |
| `GET /actuator/batches` | Spring Batch Actuator endpoint |

---

## Prerequisites

| Tool | Version |
|---|---|
| Java (JDK) | 21+ |
| Maven | 3.9+ |
| Node.js | 18+ (required by Nx) |
| Python | 3.8+ (for the CSV generator script) |

Install Nx globally (optional, the `./nx` wrapper is included):

```bash
npm install -g nx
```

---

## Running the Simulation

### Step 1 — Generate the Input CSV

The `batch-processor` ships with a pre-generated `transactions.csv` (100,000 rows, ~85% valid / ~15% invalid). To regenerate it or create a custom-sized file, use the Python generator script:

```bash
# Default: 100,000 rows, ~15% invalid, written to batch-processor/src/main/resources/data/transactions.csv
python3 scripts/generate_transactions.py

# Custom row count
python3 scripts/generate_transactions.py --rows 50000

# Custom output path
python3 scripts/generate_transactions.py --rows 100000 --out /path/to/output.csv

# Custom invalid rate (e.g. 25% invalid rows)
python3 scripts/generate_transactions.py --invalid-rate 0.25

# Reproducible output with a fixed seed
python3 scripts/generate_transactions.py --rows 100000 --seed 42

# All options combined
python3 scripts/generate_transactions.py \
  --rows 100000 \
  --out batch-processor/src/main/resources/data/transactions.csv \
  --invalid-rate 0.15 \
  --seed 42
```

**Script options:**

| Option | Default | Description |
|---|---|---|
| `--rows` | `100000` | Number of data rows to generate |
| `--out` | `batch-processor/src/main/resources/data/transactions.csv` | Output CSV file path |
| `--invalid-rate` | `0.15` | Fraction of rows that are intentionally invalid (0.0–1.0) |
| `--seed` | _(random)_ | Random seed for reproducible output |

**What makes a row invalid?** The script deliberately injects rows that will fail specific validation checks:

| Failure reason | Weight |
|---|---|
| Invalid bank code (e.g., `XENDIT`, `GOPAY`, `OVO`, `DANA`) | 30% of invalid rows |
| INACTIVE account used as source or beneficiary | 25% of invalid rows |
| BLOCKED account used as source or beneficiary | 15% of invalid rows |
| Unknown account number (not in seeded list) | 20% of invalid rows |
| Amount below the minimum for the transaction type | 10% of invalid rows |

---

### Step 2 — Start All Three Services

Each service can be run independently using Nx. Open three separate terminal tabs/windows:

**Terminal 1 — config-service:**
```bash
./nx serve config-service
```

**Terminal 2 — account-validation-service:**
```bash
./nx serve account-validation-service
```

**Terminal 3 — batch-processor:**
```bash
./nx serve batch-processor
```

Wait until all three services print their `Started ... in X seconds` banner. Startup order matters: `config-service` and `account-validation-service` should be up before `batch-processor` is used.

> **Tip:** You can build all services without running them using:
> ```bash
> ./nx run-many --target=build --all
> ```

---

### Step 3 — Trigger the Batch Job

Once all services are running, start a validation job via the REST API:

```bash
# Use the default input CSV (transactions.csv bundled in the JAR)
curl -X POST http://localhost:8083/api/v1/batch/start

# Use a custom input file
curl -X POST "http://localhost:8083/api/v1/batch/start?inputFile=/absolute/path/to/your/transactions.csv"
```

**Example response:**
```json
{
  "jobExecutionId": 1,
  "status": "STARTED",
  "inputFile": "classpath:data/transactions.csv",
  "startTime": "2026-02-20T10:15:30.123Z"
}
```

Take note of the `jobExecutionId` — you will need it to poll the status.

Alternatively, use the Swagger UI at [http://localhost:8083/swagger-ui.html](http://localhost:8083/swagger-ui.html).

---

### Step 4 — Check the Job Status

Poll the job status endpoint using the `jobExecutionId` from the previous step:

```bash
curl http://localhost:8083/api/v1/batch/status/1
```

**Example response (job in progress):**
```json
{
  "jobExecutionId": 1,
  "jobName": "transactionValidationJob",
  "status": "STARTED",
  "startTime": "2026-02-20T10:15:30.123Z",
  "endTime": null,
  "steps": [
    {
      "stepName": "workerStep:partition0",
      "status": "COMPLETED",
      "readCount": 10000,
      "writeCount": 10000,
      "skipCount": 0
    }
  ]
}
```

**Example response (job complete):**
```json
{
  "jobExecutionId": 1,
  "jobName": "transactionValidationJob",
  "status": "COMPLETED",
  "startTime": "2026-02-20T10:15:30.123Z",
  "endTime": "2026-02-20T10:16:45.678Z",
  "steps": [
    { "stepName": "workerStep:partition0", "status": "COMPLETED", "readCount": 10000, "writeCount": 10000, "skipCount": 0 },
    { "stepName": "workerStep:partition1", "status": "COMPLETED", "readCount": 10000, "writeCount": 10000, "skipCount": 0 },
    ...
  ]
}
```

---

### Step 5 — Inspect the Output Files

The batch job writes results to your system's temp directory. By default:

```
$TMPDIR/batch-output/
├── valid-p0-<timestamp>.csv
├── invalid-p0-<timestamp>.csv
├── valid-p1-<timestamp>.csv
├── invalid-p1-<timestamp>.csv
...
├── valid-p9-<timestamp>.csv
└── invalid-p9-<timestamp>.csv
```

On macOS/Linux, `$TMPDIR` is typically `/var/folders/...` or `/tmp`. To find the files:

```bash
find $TMPDIR/batch-output -name "*.csv" | sort
```

---

## Checking Results

### Via REST API

**Check if the job completed successfully:**
```bash
curl -s http://localhost:8083/api/v1/batch/status/1 | python3 -m json.tool
```
Look for `"status": "COMPLETED"`. Any `"status": "FAILED"` indicates an error in the pipeline itself (not a validation failure — those are written to the invalid CSV files).

---

### Via Output CSV Files

**Count valid vs invalid records across all partitions:**
```bash
# Total valid rows
wc -l $TMPDIR/batch-output/valid-p*.csv

# Total invalid rows
wc -l $TMPDIR/batch-output/invalid-p*.csv
```

**View the header of a valid output file:**
```bash
head -5 $TMPDIR/batch-output/valid-p0-*.csv
```

**View the header of an invalid output file:**
```bash
head -5 $TMPDIR/batch-output/invalid-p0-*.csv
```

The invalid CSV includes a `validationErrors` column at the end explaining why each record was rejected. For example:
```
referenceId,sourceAccount,...,validationErrors
TRX-0000042,6677889900,...,Source account INACTIVE: Rudi Hermawan
TRX-0000099,1234567890,...,Beneficiary bank code not valid: XENDIT
TRX-0001234,9999999999,...,Source account NOT_FOUND
```

**Find all rows with a specific error type:**
```bash
# All INACTIVE account errors
grep -h "INACTIVE" $TMPDIR/batch-output/invalid-p*.csv

# All NOT_FOUND errors
grep -h "NOT_FOUND" $TMPDIR/batch-output/invalid-p*.csv

# All invalid bank code errors
grep -h "bank code not valid" $TMPDIR/batch-output/invalid-p*.csv

# All amount-below-minimum errors
grep -h "below minimum" $TMPDIR/batch-output/invalid-p*.csv

# All BLOCKED account errors
grep -h "BLOCKED" $TMPDIR/batch-output/invalid-p*.csv
```

**Aggregate a summary of all error types:**
```bash
grep -h "" $TMPDIR/batch-output/invalid-p*.csv \
  | awk -F',' '{print $NF}' \
  | sort \
  | uniq -c \
  | sort -rn
```

---

### Via Actuator

```bash
# Overall health
curl http://localhost:8083/actuator/health

# Spring Batch job metadata (requires Spring Batch Actuator)
curl http://localhost:8083/actuator/batches

# All metrics
curl http://localhost:8083/actuator/metrics
```

---

### Via H2 Console

Open [http://localhost:8083/h2-console](http://localhost:8083/h2-console) in a browser and connect with:

| Field | Value |
|---|---|
| JDBC URL | `jdbc:h2:mem:batchdb` |
| Username | `sa` |
| Password | _(leave empty)_ |

Useful queries:

```sql
-- All job executions
SELECT * FROM BATCH_JOB_EXECUTION ORDER BY JOB_EXECUTION_ID DESC;

-- All step executions for a specific job
SELECT * FROM BATCH_STEP_EXECUTION WHERE JOB_EXECUTION_ID = 1;

-- Summary: read/write/skip counts per partition
SELECT STEP_NAME, STATUS, READ_COUNT, WRITE_COUNT
FROM BATCH_STEP_EXECUTION
WHERE JOB_EXECUTION_ID = 1
ORDER BY STEP_NAME;
```

---

## Nx Commands Reference

| Command | Description |
|---|---|
| `./nx serve config-service` | Start config-service in dev mode |
| `./nx serve account-validation-service` | Start account-validation-service in dev mode |
| `./nx serve batch-processor` | Start batch-processor in dev mode |
| `./nx build config-service` | Build config-service JAR |
| `./nx build account-validation-service` | Build account-validation-service JAR |
| `./nx build batch-processor` | Build batch-processor JAR |
| `./nx test config-service` | Run config-service tests |
| `./nx test account-validation-service` | Run account-validation-service tests |
| `./nx test batch-processor` | Run batch-processor tests |
| `./nx run-many --target=build --all` | Build all services |
| `./nx run-many --target=test --all` | Run all tests |
| `./nx clean config-service` | Clean config-service build artifacts |
| `./nx clean account-validation-service` | Clean account-validation-service build artifacts |
| `./nx clean batch-processor` | Clean batch-processor build artifacts |

> On Windows, replace `./nx` with `nx.bat`.

---

## Project Structure

```
demo-spring-batch/
├── nx.json                              # Nx workspace configuration
├── pom.xml                              # Root Maven parent POM
├── .gitignore
│
├── config-service/                      # Service 1: bank codes + tx limits API
│   ├── pom.xml
│   ├── project.json                     # Nx targets
│   └── src/
│       └── main/
│           ├── java/com/kreasipositif/configservice/
│           │   ├── ConfigServiceApplication.java
│           │   ├── controller/          # BankCodeController, TransactionLimitController
│           │   ├── config/              # OpenApiConfig, CorsConfig
│           │   └── properties/          # BankConfigProperties, TransactionConfigProperties
│           └── resources/
│               └── application.yml      # Port 8081, seed data
│
├── account-validation-service/          # Service 2: mock account validation
│   ├── pom.xml
│   ├── project.json
│   └── src/
│       └── main/
│           ├── java/com/kreasipositif/accountvalidation/
│           │   ├── AccountValidationApplication.java
│           │   ├── controller/          # AccountValidationController
│           │   ├── service/             # AccountValidationService (mock, 500ms latency)
│           │   ├── model/               # AccountValidationRequest/Response
│           │   └── config/              # OpenApiConfig, CorsConfig
│           └── resources/
│               └── application.yml      # Port 8082, 15 seeded accounts
│
├── batch-processor/                     # Service 3: Spring Batch pipeline
│   ├── pom.xml
│   ├── project.json
│   └── src/
│       ├── main/
│       │   ├── java/com/kreasipositif/batchprocessor/
│       │   │   ├── BatchProcessorApplication.java
│       │   │   ├── batch/               # RangePartitioner, FieldSetMapper, ReaderFactory,
│       │   │   │                        # ItemProcessor, ItemWriter
│       │   │   ├── client/              # ConfigServiceClient, AccountValidationClient
│       │   │   ├── config/              # BatchConfig, Resilience4jConfig, OpenApiConfig, CorsConfig
│       │   │   ├── controller/          # JobController
│       │   │   └── domain/              # TransactionRecord
│       │   └── resources/
│       │       ├── application.yml      # Port 8083, bulkhead settings, downstream URLs
│       │       └── data/
│       │           └── transactions.csv # 100,000-row input CSV
│       └── test/
│           └── java/.../BatchProcessorIntegrationTest.java
│
└── scripts/
    └── generate_transactions.py         # CSV generator (aligned with seed data)
```

---

## Bulkhead Guide — SemaphoreBulkhead vs ThreadPoolBulkhead

Resilience4j provides two bulkhead types. They solve the same problem — *preventing a downstream service from being overwhelmed* — but they work differently and suit different situations.

---

### SemaphoreBulkhead

A **semaphore** is a counter of permits. Before a call is allowed through, the caller must acquire one permit. When the call finishes, the permit is released. If all permits are taken, new callers either wait (up to `max-wait-duration`) or get a `BulkheadFullException` immediately.

```
Virtual worker thread
        │
        ▼
┌───────────────────────────┐
│  SemaphoreBulkhead        │  ← permit counter (e.g. max 20)
│  acquire permit           │
│  (blocks if full)         │
└───────────┬───────────────┘
            │  permit acquired
            ▼
    HTTP call (on this thread)
            │
            ▼
    release permit
```

**Key characteristics:**
- Runs on the **caller's own thread** — no extra threads are spawned
- The caller thread is **blocked** for the entire duration of the downstream call
- Very **lightweight** — just an `AtomicInteger` counter under the hood
- Works well with **virtual threads** (Java 21+) because blocking a virtual thread is cheap

**When to use SemaphoreBulkhead:**
| Situation | Reason |
|---|---|
| Calls are **fast** (< ~100 ms) | The caller thread isn't blocked long enough to cause congestion |
| You are using **virtual threads** | Blocking is cheap; no need for a dedicated thread pool |
| You want simple **concurrency limiting** without async complexity | Easy to reason about: at most N calls in flight at once |
| Calling a **read-heavy, low-latency** service like a config or feature-flag service | Fast responses mean permits are returned quickly |

**In this project:** used for `config-service` (bank code + amount limit lookups). These calls are fast, stateless, and naturally suited to inline execution on the virtual worker thread.

---

### ThreadPoolBulkhead

A **thread pool bulkhead** has its own dedicated thread pool and a **bounded queue**. Instead of running the call on the caller's thread, it **submits the call as a task** to the pool and returns a `CompletableFuture` immediately. If the pool threads are all busy and the queue is full, new submissions are rejected.

```
Virtual worker thread
        │
        │  submit task (non-blocking)
        ▼
┌───────────────────────────────────────────────────┐
│  ThreadPoolBulkhead                               │
│                                                   │
│  ┌──────────────────────┐   ┌────────────────┐   │
│  │  Bounded Queue       │──►│  Thread Pool   │   │
│  │  (capacity: 200)     │   │  (core: 10     │   │
│  └──────────────────────┘   │   max: 20)     │   │
│                              └───────┬────────┘   │
└──────────────────────────────────────┼────────────┘
                                       │
                                       ▼
                               HTTP call (on pool thread)
                                       │
                                       ▼
                               CompletableFuture resolved
```

**Key characteristics:**
- Runs on a **separate thread pool** — the caller thread is **not blocked**
- Returns a `CompletableFuture` — the caller can submit the task and move on
- The **bounded queue** prevents unbounded memory growth when the pool is saturated
- More complex: requires handling futures, timeouts, and `ExecutionException`
- Slightly heavier than a semaphore (thread pool overhead)

**When to use ThreadPoolBulkhead:**
| Situation | Reason |
|---|---|
| Calls are **slow** (hundreds of milliseconds) | Frees the caller thread during the wait, enabling it to do other work |
| You want to **overlap** a slow call with other work | Submit the future early, do other work, join the future at the end |
| The downstream service has **variable latency** | A dedicated pool isolates its latency from your main worker threads |
| You need a **bounded queue** to absorb burst traffic without dropping requests | Queue acts as a shock absorber: `core threads busy → queue → max threads → reject` |
| Calling a **high-latency** service like a payment gateway, account lookup, or external API | Latency needs to be decoupled from throughput |

**In this project:** used for `account-validation-service` (500 ms simulated latency). The call is submitted to the pool *before* the three sequential config-service calls begin, so it runs concurrently and its latency is hidden.

---

### Side-by-side comparison

| | SemaphoreBulkhead | ThreadPoolBulkhead |
|---|---|---|
| **Execution model** | Inline on the caller thread | Offloaded to a dedicated thread pool |
| **Caller thread** | Blocked during the call | Free immediately (non-blocking) |
| **Return type** | Direct return value | `CompletableFuture<T>` |
| **Bounded queue** | ❌ No queue — callers wait or fail | ✅ Yes — tasks queue up to `queue-capacity` |
| **Thread overhead** | None (just a counter) | Thread pool + queue management |
| **Best for** | Fast, synchronous calls | Slow calls you want to overlap with other work |
| **Works with virtual threads** | ✅ Ideal (blocking is cheap) | ✅ Yes, but adds pool overhead |
| **Config in application.yml** | `resilience4j.bulkhead` | `resilience4j.thread-pool-bulkhead` |

---

### The two-layer pattern (this project)

```
For each transaction record:

  [ThreadPoolBulkhead]                   [SemaphoreBulkhead × 3]
  submit account-validation call         Step 1: source bank code   ─┐
  → CompletableFuture in flight          Step 2: bene bank code      │ sequential on
                  │                      Step 3: amount limit        ─┘ virtual thread
                  │
                  │◄──────── join (wait for result) ──────────────────┘
                  ▼
         collect all errors
```

The `ThreadPoolBulkhead` call is fired first so it overlaps with the sequential semaphore calls. Wall-clock cost per record ≈ `max(3 × config latency, account latency)` instead of `3 × config + account`.

---

## Key Design Decisions

| Decision | Rationale |
|---|---|
| **Java 21 virtual threads** | `spring.threads.virtual.enabled=true` allows each partition worker and each HTTP call to use a lightweight virtual thread, avoiding thread-pool exhaustion under high concurrency |
| **`SemaphoreBulkhead` for config-service** | config-service calls are fast and synchronous; a semaphore limits concurrent calls without spawning extra threads |
| **`FixedThreadPoolBulkhead` for account-validation-service** | account-validation has a 500ms latency; async submission to a dedicated thread pool prevents blocking worker threads |
| **`@StepScope` on the writer** | Each partition needs its own writer instance pointing to its own output file. Making the writer step-scoped (not a singleton) prevents file contention between parallel partitions |
| **`RangePartitioner`** | Divides the CSV by line number ranges, enabling each partition to open its own `FlatFileItemReader` with a specific offset and limit — no shared reader state |
| **H2 in-memory DB** | Spring Batch requires a metadata schema. H2 provides it without any external infrastructure dependency for this demo |
| **Nx monorepo** | Keeps all three services in one repository with unified build/test/serve commands while allowing independent deployments |
