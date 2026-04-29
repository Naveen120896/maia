# MAIA — Memory-Augmented Intelligent Advisor

> A production-ready, persistent-memory AI assistant built for Financial Services  
> on **Spring Boot 3 · Java 21 · Groq · Gemini · PostgreSQL**

[![Java](https://img.shields.io/badge/Java-21-orange?style=flat-square&logo=java)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-brightgreen?style=flat-square&logo=springboot)](https://spring.io/projects/spring-boot)
[![Groq](https://img.shields.io/badge/LLM-Groq%20%7C%20Gemini%20%7C%20OpenAI-blueviolet?style=flat-square)](https://groq.com/)
[![PostgreSQL](https://img.shields.io/badge/Database-PostgreSQL-blue?style=flat-square&logo=postgresql)](https://www.postgresql.org/)
[![FSS Converge 2026](https://img.shields.io/badge/FSS%20Converge-2026%20White%20Paper-gold?style=flat-square)](/)

---

## 🧠 What is MAIA?

Most AI assistants forget you the moment a session ends. **MAIA doesn't.**

MAIA extracts personal facts from every conversation, stores them in a structured memory layer, and injects that context into every future response — **across sessions, devices, and languages.**

Built as a working proof-of-concept for the **FSS Converge 2026 GIC White Paper**, MAIA demonstrates that enterprise-grade persistent-memory AI is deployable today, in any bank's existing Java microservices stack, in under **90 days.**

---

## ✨ Key Features

| Feature | Description |
|---|---|
| 🧠 **Persistent Memory** | Extracts 100+ personal fact categories from natural conversation |
| ⚡ **SHA-256 Cache** | Repeat queries return in <50ms — ~60% API cost reduction |
| 🔁 **Multi-LLM Fallback** | Groq → Gemini → OpenAI waterfall ensures 99.9% uptime |
| 🎙️ **Voice Mode** | Full STT → LLM → TTS pipeline with auto language detection |
| 🌐 **Multilingual** | Tamil, Hindi, Arabic, Chinese, Japanese — auto detected from Unicode |
| 📄 **Document Intelligence** | PDF/TXT Q&A and AI-powered document improvement |
| 🔒 **Responsible AI** | Customer-controlled memory, full audit trail, no hallucination |

---

## 📸 Screenshots

### 1. Memory Extraction — Facts learned from natural conversation
> MAIA silently extracts personal facts and populates the memory sidebar in real time.

![Memory Extraction](https://github.com/Naveen120896/maia/blob/main/screenshots/01_memory_extraction.PNG)

---

### 2. Memory Dashboard — `show memory` command
> Structured memory dump: Summary + categorised key facts across Identity, Work, Location and more.

![Memory Dashboard](https://github.com/Naveen120896/maia/blob/main/screenshots/02_memory_dashboard.PNG)

---

### 3. Tamil Voice Response — Automatic language detection
> MAIA detects Tamil from Unicode range (U+0B80–0BFF) and responds in the customer's language automatically.

![Tamil Voice Response](https://github.com/Naveen120896/maia/blob/main/screenshots/03_tamil_voice.PNG)

---

### 4. SHA-256 Cache Hit — ⚡ instant repeat response
> Same question asked twice — second response is served from cache in <50ms with ⚡ cached badge.

![Cache Hit](https://github.com/Naveen120896/maia/blob/main/screenshots/04_cache_hit.PNG)

---

### 5. Voice Mode UI — Mic + live visualizer
> Voice-first interface with animated visualizer, live transcript, and ripple mic button.

![Voice Mode UI](https://github.com/Naveen120896/maia/blob/main/screenshots/05_voice_ui.PNG)

---

### 6. Document Q&A — PDF intelligence
> Upload any PDF — MAIA answers questions directly from the document content. Zero hallucination.

![Document QA](https://github.com/Naveen120896/maia/blob/main/screenshots/06_document_qa.PNG)

---

## 🏗️ Architecture

```
User Message
     │
     ▼
┌─────────────────────────────────────┐
│          ChatService.java           │
│                                     │
│  1. Extract facts → MemoryService   │
│  2. Check SHA-256 cache             │
│  3. Build prompt with memory        │
│  4. LLM Waterfall:                  │
│     Groq → Gemini → OpenAI          │
│  5. Save response to cache + DB     │
└─────────────────────────────────────┘
     │
     ▼
┌─────────────────────────────────────┐
│         MemoryService.java          │
│                                     │
│  LLM fact extractor → JSON          │
│  upsertMemory(userId, key, value)   │
│  rebuildSummary() after each fact   │
└─────────────────────────────────────┘
     │
     ▼
┌─────────────────────────────────────┐
│           PostgreSQL                │
│                                     │
│  chat_history   — full conversation │
│  user_memory    — key-value facts   │
│  user_summary   — NL summary        │
│  chat_cache     — SHA-256 cache     │
└─────────────────────────────────────┘
```

---

## 🛠️ Tech Stack

| Layer | Technology |
|---|---|
| **Backend** | Spring Boot 3, Java 21 |
| **Primary LLM** | Groq — llama-3.1-8b-instant |
| **Fallback LLM** | Google Gemini 1.5 Flash |
| **Last Resort LLM** | OpenAI GPT-4o Mini |
| **Database** | PostgreSQL |
| **Frontend** | Vanilla HTML · CSS · JavaScript |
| **Voice** | MediaRecorder API + Web Speech API |
| **Cache** | SHA-256 hash → PostgreSQL |

---

## 🚀 Quick Start

### Prerequisites
- Java 21+
- PostgreSQL
- Groq API key (free at [console.groq.com](https://console.groq.com))
- Gemini API key (free at [aistudio.google.com](https://aistudio.google.com))

### 1. Clone the repo
```bash
git clone https://github.com/YOUR_USERNAME/maia.git
cd maia
```

### 2. Configure API keys
```properties
# src/main/resources/application.properties

groq.api.key=your_groq_key_here
gemini.api.key=your_gemini_key_here
openai.api.key=your_openai_key_here   # optional

spring.datasource.url=jdbc:postgresql://localhost:5432/maia
spring.datasource.username=your_db_user
spring.datasource.password=your_db_password
```

### 3. Run
```bash
./mvnw spring-boot:run
```

### 4. Open the frontend
Open `index.html` in your browser — backend runs on `http://localhost:8080`

---

## 📡 REST API

| Endpoint | Method | Description |
|---|---|---|
| `/chat` | POST | Text chat with memory + cache |
| `/api/voice/chat` | POST | Multipart audio → STT → LLM → TTS |
| `/chat/document` | POST | PDF/TXT upload — Q&A or improve mode |

### Example
```bash
curl -X POST http://localhost:8080/chat \
  -H "Content-Type: application/json" \
  -d '{"userId": "naveen", "message": "My name is Naveen, I work at IBM"}'
```

---

## 🔒 Responsible AI & Governance

| Requirement | MAIA Implementation |
|---|---|
| **Explainability** | Memory sidebar shows exactly what AI knows |
| **Customer data control** | `clear logs` wipes all stored facts instantly |
| **Hallucination prevention** | System prompt: never invent facts not in memory |
| **Audit trail** | Every message timestamped in PostgreSQL |
| **No vendor lock-in** | LLM-agnostic fallback chain |

---

## 🗺️ 90-Day Deployment Roadmap

```
Phase 1 — Day 1 to 30
Deploy memory extraction service alongside existing chatbot.
No chatbot replacement needed.

Phase 2 — Day 31 to 60
Integrate LLM fallback chain and SHA-256 semantic cache layer.

Phase 3 — Day 61 to 90
Enable voice mode and document intelligence for target segments.
```

---

## 📄 White Paper

This project is the working proof-of-concept behind the **FSS Converge 2026 GIC White Paper**:

> *"The Always-On Financial Advisor: How Persistent-Memory AI Redefines Hyper-Personalisation in Banking"*  
> Category: ReInvent Product & Service Delivery — Hyper-Personalized Experiences & Lifetime Value Engagement

---

## 👤 Author

**Naveen** — Java Full Stack Developer, IBM India GIC  
FSS Converge 2026 White Paper Submission · April 2026

---

*Source code available for IBM internal review on request.*
