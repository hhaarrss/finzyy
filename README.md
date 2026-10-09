# 💸 Finzyy

An AI-augmented personal expense tracker built for the Indian UPI/banking ecosystem — turns passive bank SMS alerts into structured, categorized financial analytics in real time.

[![Backend](https://img.shields.io/badge/backend-FastAPI-009688?logo=fastapi&logoColor=white)](#tech-stack)
[![Database](https://img.shields.io/badge/database-PostgreSQL-4169E1?logo=postgresql&logoColor=white)](#tech-stack)
[![Android](https://img.shields.io/badge/android-Kotlin-7F52FF?logo=kotlin&logoColor=white)](#tech-stack)
[![License](https://img.shields.io/badge/license-MIT-green)](#license)

---

## 📌 The Problem

Most personal budgeting apps fail for one simple reason: **users hate manually typing every transaction**. People download a budgeting app with good intentions and abandon it within weeks because logging every coffee, grocery run, and UPI transfer by hand is friction nobody sticks with.

**Finzyy** removes that friction entirely. It listens for the SMS your bank already sends you on every transaction, and turns that into a categorized, analyzed entry — automatically.

---

## ✨ Key Features

* 🔕 **Zero-friction passive ingestion** — a native Android `BroadcastReceiver` listens for bank debit/credit SMS alerts and syncs them without any user action.
* 🧠 **5-layer hybrid merchant categorization** — a waterfall pipeline combining user-corrected mappings, a curated merchant database, ISO Merchant Category Code (MCC) lookups, keyword pattern matching, and a confidence-scored fallback — so accuracy improves the more you use it.
* 📶 **Offline-resilient sync** — transactions captured with no network connection are queued locally and auto-flushed once connectivity returns.
* 🔁 **Idempotent by design** — every transaction is fingerprinted (SHA-256) before insertion, preventing duplicate entries when a bank re-sends or retries an SMS broadcast.
* 📊 **In-app analytics** — the Android app shows spending by category and merchant, trends over time, budget utilization, and month-over-month change.
* 🎯 **Budget limits & smart alerts** — per-category monthly budgets with utilization tracking and over-limit warnings.
* 🔎 **Continuous learning loop** — every manual re-categorization a user makes trains the categorizer for that merchant going forward, so the system needs less correction over time.

---

## 🏗️ System Architecture

```text
[ BANK SMS ALERT ]
       │
       ▼
┌───────────────────────┐
│     Android App       │
│    (SmsReceiver)      │
└───────────┬───────────┘
            │ (Offline Queue if no connection)
            ▼
 HTTP REST API (JWT Bearer)
┌───────────────────────┐
│    FastAPI Backend    │
│   (Uvicorn / Async)   │
└───────────┬───────────┘
            │
 ┌──────────┴───────────────────────────────┐
 ▼                                          ▼
┌─────────────────────────┐        ┌─────────────────────────┐
│    SMS Parser Engine    │        │   JWT Auth & Security   │
│   (Regex Extraction)    │        │    (OAuth2 / Bcrypt)    │
└────────────┬────────────┘        └─────────────────────────┘
             │
             ▼
┌─────────────────────────┐
│   5-Layer Categorizer   │
│ (Merchant, MCC, Rules,  │
│    User Learning, DB)   │
└────────────┬────────────┘
             │
             ▼
 SHA-256 Fingerprint Deduplication
┌─────────────────────────┐
│      PostgreSQL DB      │
│  (Asyncpg / SQLAlchemy) │
└────────────┬────────────┘
             │
             ▼
 REST API (FastAPI)
┌─────────────────────────┐
│   Android app screens   │
│  (Charts & Analytics)   │
└─────────────────────────┘
```

---

## 🛠️ Tech Stack

| Layer | Technology | Key Libraries |
|---|---|---|
| **Backend API** | Python 3.11 | FastAPI, Uvicorn, Pydantic v2 |
| **Database** | PostgreSQL | SQLAlchemy 2.0 (Async), asyncpg, Alembic |
| **Security / Auth** | Auth Subsystem | PyJWT, passlib / bcrypt |
| **Android App** | Native Kotlin | Jetpack Compose, Retrofit2, Gson, OkHttp3, Coroutines |

### Why these choices?
* **Async SQLAlchemy + asyncpg** over a synchronous driver like psycopg2 — a sync driver would block FastAPI's event loop during every DB call, defeating the purpose of an async framework.
* **SHA-256 fingerprinting** for deduplication instead of relying on client-side checks — bank SMS broadcasts can fire more than once, and idempotency has to be enforced server-side to be trustworthy.
* **JWT Bearer auth** for a stateless API that scales horizontally without server-side session storage.

---

## 🧩 How the Categorizer Works

Every transaction runs through a 5-layer waterfall until it finds a confident match:

1. **User Corrections** — has this exact merchant been manually corrected before by this user? Use that.
2. **Known Merchant Database** — matches against 255+ pre-indexed Indian merchants (Swiggy, Zomato, Amazon, Uber, DMart, etc.).
3. **MCC Code Matching** — falls back to 981 standard ISO Merchant Category Codes.
4. **Keyword Pattern Matching** — regex heuristics for common terms (pharmacy, fuel, recharge).
5. **Confidence Scoring** — high-confidence matches are auto-categorized; weak or missing matches are flagged *Needs Review* for a one-tap user correction, which is fed back into Layer 1 for next time.

---

## 📱 Screenshots

> _Live dashboard, add transaction, budget limits, and AI insights views._

| Dashboard | Add Transaction | Budget Limits | AI Insights |
|:---:|:---:|:---:|:---:|
| ![dashboard](docs/screenshots/dashboard.png) | ![add-transaction](docs/screenshots/add-transaction.png) | ![budget](docs/screenshots/budget.png) | ![insights](docs/screenshots/insights.png) |

---

## 🚀 Getting Started

### Prerequisites
* Python 3.11+
* PostgreSQL 14+
* Android Studio (for the mobile app)

### Backend setup
```bash
cd backend
python -m venv venv
source venv/bin/activate  # Windows: venv\Scripts\activate
pip install -r requirements.txt

# Configure environment
cp .env.example .env  # set DATABASE_URL, JWT_SECRET, etc.

# Run migrations
alembic upgrade head

# Start the API
uvicorn main:app --reload
```

### Android app
Open the `android/` directory in Android Studio, sync Gradle, and run on a device or emulator with SMS permissions granted.

---

## 📊 Project Highlights

* **255+** pre-indexed Indian merchant mappings
* **981** ISO Merchant Category Codes supported
* **< 40ms** average backend categorization latency per SMS
* **100% idempotency** on duplicate SMS broadcasts, enforced via SHA-256 fingerprinting

---

## 🗺️ Roadmap

- [ ] **Unified fingerprint formula** across all ingestion paths (manual, SMS, PDF) to eliminate cross-channel duplicate risk
- [ ] **Personal (P2P) transfer detection**, separate from merchant purchases, for more accurate spending totals
- [ ] **Recurring subscription detection** for weekly and annual billing cycles, in addition to monthly
- [ ] **Redis caching layer** for merchant/MCC lookups at scale
- [ ] **Celery/RabbitMQ background workers** to offload categorization from the request thread

---

## 🧪 Engineering Notes

This project intentionally documents its own known trade-offs and edge cases rather than hiding them — see [docs/architecture.md](docs/architecture.md) for deeper write-ups on:
* The 5-layer categorization pipeline and its confidence scoring
* Deduplication strategy across multiple transaction-ingestion paths
* Scaling considerations for moving from single-instance to 1M+ users (PgBouncer, read replicas, async task queues)

---

## 📄 License

This project is licensed under the MIT License — see [LICENSE](LICENSE) for details.

---

## 🙋 About

Built by **Harsh Rabadiya** as a full-stack, cross-platform exploration of passive financial data ingestion for the Indian UPI ecosystem — spanning a native Android app and an async Python backend.

📫 **Reach out**: [harshr4834@gmail.com](mailto:harshr4834@gmail.com) · [LinkedIn Profile](https://www.linkedin.com/in/harsh-rabadiya-828683265/)
