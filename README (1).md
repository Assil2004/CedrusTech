# CedrusTech HR System


we choose topic A
Assil chehade 6704
ali ataya 6756
mohammad hammoud 6460
mohammad salloum 6494
CedrusTech HR System is a real-time concurrent HR platform built with Java 17 and Spring Boot, handling 50+ simultaneous WebSocket clients through three bounded thread pools, backpressure, and a full CompletableFuture async pipeline. It integrates a Python RAG chatbot powered by Ollama llama3 and ChromaDB for intelligent company Q&A, an AI agent for automated CV scoring and candidate ranking, and a shared SQL Server database with distributed tracing and live concurrency metrics.
---

## Table of Contents

- [Overview](#overview)
- [System Architecture](#system-architecture)
- [Project 1 — Java Concurrent Backend](#project-1--java-concurrent-backend)
- [Project 2 — Python AI Chatbot](#project-2--python-ai-chatbot)
- [Project 3 — HR AI Agent](#project-3--hr-ai-agent)
- [Prerequisites](#prerequisites)
- [Setup & Running](#setup--running)
- [API Endpoints](#api-endpoints)
- [Concurrency Design](#concurrency-design)
- [Database Schema](#database-schema)
- [File Structure](#file-structure)
- [Environment Variables](#environment-variables)

---

## Overview

CedrusTech HR System is a full-stack HR platform built for a concurrent programming course. It consists of three integrated components:

| Component | Technology | Purpose |
|---|---|---|
| **Java Backend** | Spring Boot 3 + WebSocket | Concurrent server — 50+ clients, 3 thread pools, metrics |
| **Python Chatbot** | FastAPI + Ollama llama3 | AI assistant answering company questions |
| **HR AI Agent** | Python + LangGraph | CV analysis, ranking, decision engine |

All three share the same **SQL Server database (MH_AC)** and work together in a unified HR workflow.

---

## System Architecture

```
Browser (HTML/CSS/JS)
        │
        ├── WebSocket  ws://localhost:8081/ws/chat   ← real-time chat
        └── HTTP POST  http://localhost:8081/apply    ← job applications
                            │
                  ┌─────────┴──────────┐
                  │  Java Spring Boot  │  port 8081
                  │  (Concurrency Engine)│
                  │                    │
          wsExecutor  aiExecutor  appExecutor
          (core10/max50)(core3/max5)(core5/max20)
                  │
                  └── HTTP POST http://localhost:8000/chat
                            │
                  ┌─────────┴──────────┐
                  │  Python FastAPI    │  port 8000
                  │  (RAG + Ollama)   │
                  │                   │
                  │  Multi-query RAG  │
                  │  ChromaDB vectors │
                  │  llama3 inference │
                  └───────────────────┘
                            │
                  ┌─────────┴──────────┐
                  │    SQL Server      │
                  │    MH_AC           │
                  │                   │
                  │  applicants        │
                  │  event_log         │
                  │  agent_logs        │
                  └───────────────────┘
                            ↑
                  ┌─────────┴──────────┐
                  │  HR AI Agent       │
                  │  (hr_agent/)       │
                  │                   │
                  │  
                  │  CV Parser        │
                  │  Decision Engine  │
                  │  Ranking Engine   │
                  │  Email Agent      │
                  └───────────────────┘
```

---

## Project 1 — Java Concurrent Backend

The Java backend is the concurrency engine of the system, implementing every concept from the concurrent programming course syllabus.

### Concurrency Features

| Week | Concept | Implementation |
|---|---|---|
| 1–2 | Concurrent entry points, session lifecycle | WebSocket `/ws/chat`, HTTP `/apply`, `/chat` |
| 3 | Race conditions, locks, deadlock avoidance | `ConcurrentHashMap`, `synchronized(session)`, `ReadWriteLock` |
| 4 | Visibility, happens-before, volatile, immutability | `volatile maintenanceMode`, `ChatMessage` / `ApiResponse` records |
| 5 | ExecutorService, bounded queues, backpressure, atomics | 3 `ThreadPoolExecutor`s, `LinkedBlockingQueue`, `LongAdder` |
| 6 | Fork/Join, task granularity, break-even | `ForkJoinPool` CV analysis, 500-word break-even threshold |
| 7 | CompletableFuture, async pipelines, graceful shutdown | `.orTimeout(30s).exceptionally()`, `GracefulShutdownManager` |
| 9 | Python GIL, runtime boundaries | `aiExecutor` max=5, `Semaphore(4)`, HTTP/1.1 forced |
| 10–14 | Event log, idempotency, distributed tracing, retry | `event_log` table, `correlationId` chain, retry with backoff |

### Three Thread Pools

```
wsExecutor  — core 10 · max 50 · queue 100  (WebSocket chat)
aiExecutor  — core 3  · max 5  · queue 20   (Python AI calls, GIL-limited)
appExecutor — core 5  · max 20 · queue 50   (DB writes + file saves)
```

### Key Files

```
src/main/java/com/cedrustech/hrsystem/
├── HrsystemApplication.java          ← entry point + shutdown hook
├── config/
│   ├── ExecutorConfig.java           ← 3 thread pools with sizing rationale
│   ├── WebSocketConfig.java          ← /ws/chat endpoint registration
│   └── CorsConfig.java               ← CORS for frontend
├── controller/
│   ├── ChatRestController.java       ← POST /chat (DeferredResult async)
│   ├── ApplicationController.java    ← POST /apply (CompletableFuture async)
│   └── MetricsController.java        ← GET /metrics + /analysis + /trace
├── service/
│   ├── ChatService.java              ← ReadWriteLock + volatile maintenance mode
│   ├── AIProxyService.java           ← Semaphore + circuit breaker + retry
│   ├── ApplicationService.java       ← CV save + DB persist (always Pending)
│   └── EventLogService.java          ← async fire-and-forget event logging
├── websocket/
│   ├── ChatWebSocketHandler.java     ← CompletableFuture pipeline + idempotency
│   └── SessionManager.java           ← ConcurrentHashMap session registry
├── metrics/
│   ├── MetricsCollector.java         ← LongAdder + AtomicLong + p50/p95
│   └── ConcurrencyAnalysisService.java ← Amdahl's Law + Little's Law live
├── model/
│   ├── ChatMessage.java              ← immutable record
│   ├── ApiResponse.java              ← immutable record
│   ├── Applicant.java                ← JPA entity
│   └── EventLog.java                 ← JPA entity
├── queue/
│   └── BoundedMessageQueue.java      ← per-session bounded queue
├── repository/
│   ├── ApplicantRepository.java
│   └── EventLogRepository.java
└── shutdown/
    └── GracefulShutdownManager.java  ← ordered 4-step drain
```

---

## Project 2 — Python AI Chatbot

A RAG-powered chatbot using Ollama llama3 and ChromaDB. Serves as the AI brain behind the Java backend.

### RAG Pipeline

```
Question
   ↓
Multi-Query Expansion (3 variants)
   ↓
ChromaDB Vector Search (nomic-embed-text)
   ↓
Score Fusion: vector × 0.65 + keyword × 0.35
   ↓
Top-5 chunk context → llama3
   ↓
Answer
```

### Concurrency Fix

```python
# BEFORE (wrong): blocks FastAPI event loop → 5 clients wait in series
answer = ask_chatbot(message)

# AFTER (correct): thread pool → 5 clients run in parallel
answer = await loop.run_in_executor(_ollama_executor, ask_chatbot, message)
```

### Key Files

```
backend/
├── api.py              ← FastAPI app + ThreadPoolExecutor(4)
├── chatbot.py          ← per-session thread-safe history + RAG
└── rag/
    ├── loader.py       ← PDF extraction + section detection
    ├── chunker.py      ← section-aware chunking with 150-char overlap
    ├── embeddings.py   ← build ChromaDB (run once)
    ├── vectorstore.py  ← thread-safe ChromaDB singleton
    └── retriever.py    ← multi-query + keyword scoring + reranking
```

### Build the Knowledge Base

```bash
cd backend
python rag/embeddings.py
```

---

## Project 3 — HR AI Agent

A LangGraph-based AI agent that processes CVs, scores candidates, and sends decision emails. Runs separately from the web server — triggered manually by the HR manager.

### Agent Workflow

```
PDF CVs (cvs/ folder)
        ↓
cv_parser.py     → extract text + llama3 parse → candidate dict
        ↓
decision_engine.py → score + classify → STRONG ACCEPT / ACCEPT / INTERNSHIP / REJECT
        ↓
ranking_engine.py  → rank accepted pool → top N
        ↓
email_agent.py     → send decision emails (stub → real SMTP later)
        ↓
MH_AC SQL Server   → update application_status
```

### Key Files

```
hr_agent/
├── agent.py            ← main runner (LangGraph orchestration)
├── cv_parser.py        ← PDF text + llama3 structured parse
├── decision_engine.py  ← scoring: AI keywords, skills, experience, education
├── ranking_engine.py   ← weighted scoring + top-N selection
├── email_agent.py      ← email templates (STRONG ACCEPT / ACCEPT / INTERNSHIP / REJECT)
├── domain_data/
│   ├── company_info.json   ← CEO, mission, vision, departments, services
│   ├── job_positions.json  ← 6 positions with required/preferred skills
│   └── hr_policies.json    ← PTO, schedule, remote work, code of conduct
└── database/
    └── db.py           ← MH_AC connection + agent_logs table
```

### Run the Agent

```bash
cd hr_agent
python agent.py
```

---

## Prerequisites

| Requirement | Version | Notes |
|---|---|---|
| Java | 17+ | OpenJDK or Oracle |
| Maven | 3.8+ | for `mvn spring-boot:run` |
| Python | 3.11+ | for backend + hr_agent |
| Ollama | latest | must be running locally |
| SQL Server | 2019+ | database: MH_AC |
| ODBC Driver | 17 | for Python pyodbc |

### Python packages

```bash
pip install fastapi uvicorn ollama chromadb pypdf pyodbc langgraph
```

### Ollama models

```bash
ollama pull llama3
ollama pull nomic-embed-text
```

---

## Setup & Running

### Step 1 — Database

Run `schema.sql` in SQL Server Management Studio against the `MH_AC` database:

```sql
USE MH_AC;
-- creates: applicants, event_log tables
-- agent_logs created automatically by hr_agent/database/db.py
```

### Step 2 — Python Chatbot (port 8000)

```bash
cd backend

# Build knowledge base (first time only)
python rag/embeddings.py

# Start server
uvicorn api:app --host 0.0.0.0 --port 8000 --reload
```

### Step 3 — Java Backend (port 8081)

```bash
# Must be run from the folder containing pom.xml
cd hrsystem
mvn spring-boot:run
```

### Step 4 — Frontend

Open `frontend/index.html` in your browser (no server needed).

### Step 5 — HR Agent (on demand)

```bash
cd hr_agent
python agent.py
```

---

## API Endpoints

### Java Backend (port 8081)

| Method | Endpoint | Description |
|---|---|---|
| `WS` | `/ws/chat` | Real-time WebSocket chat |
| `POST` | `/chat` | REST chat alternative |
| `POST` | `/apply` | Submit job application (multipart) |
| `GET` | `/metrics` | Full system metrics snapshot |
| `GET` | `/metrics/pools` | Thread pool details |
| `GET` | `/metrics/health` | UP/DOWN check |
| `GET` | `/metrics/analysis` | Amdahl's Law + Little's Law live |
| `GET` | `/metrics/trace/{correlationId}` | Distributed trace for one message |
| `GET` | `/metrics/session/{sessionId}` | Audit trail for one session |

### Python Backend (port 8000)

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/` | Health check |
| `GET` | `/health` | Status + worker count |
| `POST` | `/chat` | AI chat (called by Java) |

---

## Concurrency Design

### Amdahl's Law

```
S (serial fraction) = 0.875   ← Ollama inference
N (parallel workers) = 4

Speedup = 1 / (S + (1-S)/N)
        = 1 / (0.875 + 0.125/4)
        = 1.14×

Key insight: gain is concurrent clients (4 run in parallel)
not per-request speed (Ollama is the serial bottleneck)
```

### Little's Law

```
L = λ × W
λ = throughput (req/s)
W = avg latency (s)

At 5 clients, W=16s:
λ = 0.25 req/s
L = 0.25 × 16 = 4   ← well under queue capacity of 100
```

### Backpressure Chain

```
Client sends message
    ↓
BoundedMessageQueue.offer() → false if full (20/session)
    ↓
BackpressureRejectionHandler → HTTP 503 if executor full
    ↓
metrics.messageDropped++ / metrics.requestRejected++
    ↓
GET /metrics shows it all
```

---

## Database Schema

### `applicants` table (managed by Java)

```sql
applicant_id       BIGINT IDENTITY PRIMARY KEY
first_name         NVARCHAR(100)
last_name          NVARCHAR(100)
email              NVARCHAR(255) UNIQUE
phone              NVARCHAR(30)
position_id        BIGINT
application_status NVARCHAR(30)   -- Pending → ACCEPT/REJECT/INTERNSHIP
resume             NVARCHAR(MAX)
```

### `event_log` table (managed by Java)

```sql
id             BIGINT IDENTITY PRIMARY KEY
correlation_id NVARCHAR(64)    -- UUID per message (distributed tracing)
event_type     NVARCHAR(30)    -- CHAT_RECEIVED | CHAT_DELIVERED | APP_SAVED ...
session_id     NVARCHAR(64)
payload        NVARCHAR(1000)
status         NVARCHAR(20)    -- SUCCESS | FAILED | PENDING
attempt        INT
timestamp      DATETIME2
```

### `agent_logs` table (managed by HR Agent)

```sql
id           INT IDENTITY PRIMARY KEY
session_id   NVARCHAR(64)
event_type   NVARCHAR(50)    -- TOOL_CALL | TOOL_RESULT | INTENT | FALLBACK
intent       NVARCHAR(50)
tool_name    NVARCHAR(100)
tool_input   NVARCHAR(MAX)
tool_result  NVARCHAR(MAX)
error        NVARCHAR(MAX)
iteration    INT
timestamp    DATETIME2
```

---

## File Structure

```
cedrustech_ai/
│
├── hrsystem/                          ← Java Spring Boot project
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/cedrustech/hrsystem/
│       │   ├── HrsystemApplication.java
│       │   ├── config/               (ExecutorConfig, WebSocketConfig, CorsConfig)
│       │   ├── controller/           (ChatRest, Application, Metrics)
│       │   ├── service/              (Chat, AIProxy, Application, EventLog)
│       │   ├── websocket/            (ChatWebSocketHandler, SessionManager)
│       │   ├── metrics/              (MetricsCollector, ConcurrencyAnalysisService)
│       │   ├── model/                (ChatMessage, ApiResponse, Applicant, EventLog)
│       │   ├── queue/                (BoundedMessageQueue)
│       │   ├── repository/           (ApplicantRepository, EventLogRepository)
│       │   └── shutdown/             (GracefulShutdownManager)
│       └── resources/
│           └── application.properties
│
├── backend/                           ← Python RAG Chatbot
│   ├── api.py                         ← FastAPI + ThreadPoolExecutor(4)
│   ├── chatbot.py                     ← RAG chatbot + thread-safe history
│   ├── data/
│   │   └── CedrusTech Solutions.pdf   ← knowledge source
│   ├── chroma_db/                     ← ChromaDB vector store (auto-generated)
│   └── rag/
│       ├── loader.py
│       ├── chunker.py
│       ├── embeddings.py              ← run once to build ChromaDB
│       ├── vectorstore.py
│       └── retriever.py
│
├── frontend/                          ← HTML/CSS/JS
│   ├── index.html
│   ├── public/
│   │   ├── script.js
│   │   └── style.css
│   └── src/
│
├── hr_agent/                          ← HR AI Agent
│   ├── agent.py                       ← LangGraph orchestrator (run manually)
│   ├── cv_parser.py                   ← PDF extract + llama3 parse
│   ├── decision_engine.py             ← candidate scoring + decision
│   ├── ranking_engine.py              ← top-N ranking
│   ├── email_agent.py                 ← email templates
│   ├── domain_data/
│   │   ├── company_info.json
│   │   ├── job_positions.json
│   │   └── hr_policies.json
│   └── database/
│       └── db.py                      ← MH_AC connection + agent_logs
│
├── cvs/                               ← CV files uploaded via website
├── schema.sql                         ← SQL Server table creation script
└── README.md
```

---

## Environment Variables

All credentials are read from environment variables. Never commit real values.

| Variable | Default | Description |
|---|---|---|
| `DB_SERVER` | `localhost,1433` | SQL Server address |
| `DB_NAME` | `MH_AC` | Database name |
| `DB_USER` | `sa` | SQL Server username |
| `DB_PASS` | `sola123` | SQL Server password |

Set them in PowerShell before running:

```powershell
$env:DB_SERVER = "localhost,1433"
$env:DB_NAME   = "MH_AC"
$env:DB_USER   = "sa"
$env:DB_PASS   = "sola123"
```

Or in `application.properties` for the Java backend (already configured).

---

## Metrics Live Demo

After starting both servers, open in browser:

```
http://localhost:8081/metrics          ← full snapshot
http://localhost:8081/metrics/analysis ← Amdahl + Little's Law live
http://localhost:8081/metrics/pools    ← thread pool details
http://localhost:8081/metrics/health   ← UP/DOWN
```

---

## Authors

**Assil Shehade** — CEO, CedrusTech Solutions  
Concurrent & Distributed Programming Course — June 2026

---

<div align="center">
CedrusTech Solutions · Beirut, Lebanon · CedrusTech.hr@gmail.com
</div>
