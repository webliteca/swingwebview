---
description: Reverse-engineer REASONS Canvases from an existing codebase
argument-hint: "[optional comma-separated feature hints to focus on]"
---

<!--
litecode-only SPDD skill. There is no upstream gszhangwei/open-spdd
counterpart for /spdd-bootstrap — this skill exists to reverse-engineer
REASONS Canvases from an existing codebase that pre-dates SPDD.
-->

# /spdd-bootstrap

Reverse-engineer one REASONS Canvas per cohesive user-facing feature
from the **current state of the codebase**, so an existing project can
adopt SPDD without writing every Canvas by hand.

This is the inverse of the normal SPDD flow: code already exists, and
we want Canvases that describe what is actually there — not what
should be there. The article at
<https://martinfowler.com/articles/structured-prompt-driven/> assumes
canvases are authored prospectively; this command fills the gap for
brownfield projects.

## Inputs
- `$ARGUMENTS` — optional comma-separated feature hints (e.g.
  "billing, auth, webhooks") to focus the scan. If empty, discover
  features from the codebase.

## Constraints
- Canvases describe **intent and behavior**, not file layout. A
  "feature" is something a user or external caller would name (e.g.
  "Multi-Plan Billing"), not a package or class.
- **Ground every claim in code.** When you assert a rule, cite the
  `path/to/File.java:42` that enforces it. If you can't find evidence,
  mark the bullet `[INFERRED]` rather than inventing.
- Prefer **fewer, larger Canvases** over many small ones. Aim for
  5–15 features for a typical service. If the codebase suggests
  more, bias toward grouping rather than splitting.
- **Read-only during discovery.** No code edits at any point — this
  command only writes Canvas files under `spdd/prompt/`.
- **Never duplicate** an existing Canvas in `spdd/prompt/`. Skip and
  list it as pre-existing in the summary.

## Process

### 1. Discover features
Scan the codebase for entry points that suggest user-facing
capabilities:
- HTTP controllers / REST endpoints
- CLI commands and main classes
- UI routes, panels, dialogs, menu actions
- Scheduled jobs, message consumers, webhook handlers
- Public APIs / SDK surfaces
- Domain packages with cohesive responsibilities

For each candidate feature collect: a name, a one-line description,
and 3–5 primary code anchors (file paths). Apply `$ARGUMENTS` as a
filter if provided.

Read `spdd/prompt/` first and skip any feature that already has a
Canvas (match by description / kebab-name).

### 2. Pick the next available id
List `spdd/prompt/` and find the highest existing
`<ID>-...-[Feat]-...md`. Start numbering from `max + 1`.

### 3. Generate one Canvas per feature
For each feature, write
`spdd/prompt/<id>-<YYYYMMDD>-<HHMM>-[Feat]-<Kebab-Description>.md`
using the REASONS schema. Use local time. Increment `<id>` per
Canvas. Re-use the same `<YYYYMMDD>-<HHMM>` across the batch is fine.

Each section must be substantive — bullets, not prose; method
signatures and entity invariants where they exist; explicit citations
to `path:line`. The goal is a Canvas that `/spdd-sync` would consider
in-sync with the current code.

## Per-Canvas file contents
```
---
bootstrap: true
generated_at: <ISO timestamp>
---

# REASONS Canvas: <Title>

## R · Requirements
- Observable behavior, inputs, outputs.
- Who calls this feature (UI / API / scheduler / etc.).
- Definition of Done as it stands today (existing tests, acceptance
  paths). Cite `src/test/...` files where relevant.

## E · Entities
- Domain types and their invariants. Cite the class file:line that
  defines each invariant.

## A · Approach
- The strategy actually chosen in the code, and the visible
  trade-offs (sync vs async, in-process vs queue, etc.).

## S · Structure
- Modules / packages / classes that implement this feature, with
  paths. One bullet per significant component.

## O · Operations
- One numbered subsection per significant component implemented by
  this feature (entities, services, controllers, repositories, UI
  panels, MCP tools, schedulers, etc.). Use the Structure section as
  the input list — every component there should have an Operations
  entry.
- Format each entry like canvas 12 (`12-…-Inline-Mermaid-…md`) or
  the upstream example
  (https://github.com/gszhangwei/token-billing/blob/iteration-1-end/spdd/prompt/GGQPA-XXX-202603131758-%5BFeat%5D-api-token-usage-billing.md):
  ```
  ### N. <Verb> <ComponentType> — <Name>
  File: <path/to/File.java>

  1. Responsibility: <one-line purpose>.
  2. Fields / Attributes: <name: Type — short description>.
  3. Methods:
     - <signature>: <return type>
       - Logic: <ordered steps>; cite `path/to/File.java:line` where
         the code already implements this.
  4. Constraints / Invariants: anything the impl enforces.
  ```
- Order entries by dependency (entities → mappers → repositories →
  services → controllers → UI/MCP), so the section reads as a build
  plan that `/spdd-generate` could walk in order.
- **Operations is a per-component task list, not a list of runtime
  notes.** Persistence locations, polling intervals, retry policies,
  rate limits, observability hooks are *invariants* — they belong in
  Norms (or Approach for high-level strategy choices), not here.
  Operations entries reference those invariants from inside the
  relevant component's Logic steps; they do not stand alone as
  bullets.

## N · Norms
- Conventions this feature follows (logging, error handling, test
  layout). Mark any deviations from `spdd/norms.md` as `[DRIFT]`.

## S · Safeguards
- Validation, authorization checks, failure modes, and what happens
  when each fails. Cite the guard's `file:line`.
```

## After writing
Print a single summary message:
- Canvases created: `<count>` — list each path.
- Canvases skipped (pre-existing): list them.
- Features considered but dropped (with one-line reason each).
- Suggested next steps: review each Canvas, then run `/spdd-sync` on
  any that already drift from the code.
