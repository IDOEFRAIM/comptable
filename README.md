# 📊 Comptable — AI-Powered Accounting SaaS Platform

> **Automate accounting workflows, centralize financial operations, and assist accounting professionals with artificial intelligence.**

**Comptable** is a modern SaaS platform designed for **accounting firms and their business clients**.

It centralizes accounting, tax, and document management while integrating an AI layer capable of analyzing supporting documents, detecting anomalies, matching transactions, and assisting accountants in their decision-making.

---

## Why Comptable?

Accounting firms deal every day with:

- 📄 Invoices and supporting documents
- 🏦 Bank statements
- 💰 Mobile Money transactions
- 📊 Accounting entries
- 🧾 Tax returns
- 👥 Data from multiple companies
- 📱 Documents submitted through WhatsApp

Comptable transforms these data flows into a **structured, controlled, and increasingly automated accounting process**.

### 🎯 Goals

- ⚡ Reduce manual data entry
- 🤖 Automate document analysis
- 🔎 Detect anomalies and financial risks
- 🔗 Simplify bank and Mobile Money reconciliation
- 📱 Enable document ingestion through WhatsApp
- 📊 Provide accounting firms with a consolidated view of their clients
- 🔐 Guarantee data isolation between accounting firms
- 👨‍💼 Keep humans in the loop for sensitive decisions

---

# 🚀 Features

## 🏢 Multi-Tenant Architecture

Each accounting firm operates as an **independent tenant** with its own users and client companies.

The platform supports four main roles:

| Role | Description |
|---|---|
| `SUPER_ADMIN` | Global platform administrator |
| `EXPERT_COMPTABLE` | Accounting firm manager |
| `COLLABORATEUR` | Accounting firm employee |
| `CLIENT` | Client company user |

Authentication is based on **JWT tokens** containing the user's identity, tenant, and roles.

---

## 📚 Accounting Management

The core accounting module provides:

- Accounting periods
- Chart of accounts
- Accounting journals
- General ledger entries
- Trial balance
- General ledger
- Journal reports
- Financial statements
- Fiscal year closing
- Account reconciliation
- Bank reconciliation
- Fixed assets and depreciation
- Quotes and invoices
- FEC export

The platform also includes a **SYSCOHADA Revised** chart of accounts.

---

# 📄 Document Management

Companies can upload their documents directly through the platform.

Supported document types include:

- `FACTURE`
- `BULLETIN_PAIE`
- `RELEVÉ_BANCAIRE`
- `DEVIS`
- `AUTRE`

The configured maximum file size is **10 MB per document**.

Each document follows a processing lifecycle:

```text
RECEIVED
   ↓
PROCESSING
   ↓
PROCESSED
   ↓
ACCOUNTED
   ↓
ARCHIVED
