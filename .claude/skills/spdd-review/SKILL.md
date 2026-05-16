---
description: Review a pull request through the SPDD lens (spec ↔ code conformance)
argument-hint: "[optional PR number, branch, or path to a Canvas file]"
---

<!--
litecode-only SPDD skill. Upstream gszhangwei/open-spdd ships a similar
command as /spdd-code-review under its optional set; this is litecode's
own variant tailored to the PR-review workflow (see spdd-code-review
upstream if you want a fuller implementation).
-->

# /spdd-review

Review a pull request through the SPDD lens. The REASONS Canvas under
`spdd/prompt/` is the source of truth; code is generated from it. So
review is mostly about checking the spec ↔ code relationship rather
than just reading the diff.

## Inputs
- `$ARGUMENTS` — optional. May be a PR number, a branch name, or a
  path to the relevant Canvas under `spdd/prompt/`. If empty, review
  the diff between the current branch and its base, and locate the
  Canvas whose Operations overlap most with the changed files.

## Process

Walk the checklist below in order. For each item, leave a concrete
finding — either "OK" with the line(s) that justify it, or a review
comment with file/line and a suggested fix.

### 1. The PR touches the right artifacts
- **Behavioural change** (new requirement, changed business rule,
  fixed logic gap): expect a Canvas update via `/spdd-prompt-update`
  **first**, then code from `/spdd-generate`. Code-only diffs for
  behavioural changes are a red flag — push back.
- **Pure refactor / rename / structural cleanup**: expect
  `/spdd-sync` to update only `S · Structure` and `O · Operations`.
  `R`, `E`, `A` should be untouched.
- **New feature**: expect the full chain — story under
  `requirements/`, analysis under `spdd/analysis/`, Canvas under
  `spdd/prompt/`, then code.

### 2. Canvas ↔ code traceability
Per `/spdd-generate`'s contract: anything in the code that isn't
traceable to a Canvas line is a sign the spec is incomplete.
- For each non-trivial code change, find the corresponding line in
  `O · Operations` or `S · Structure`. If you can't, the Canvas is
  under-specified.
- Conversely, every Canvas Operation should have implementing code.
- Watch for **scope creep**: opportunistic refactors, drive-by
  helpers, speculative abstractions not prescribed by the Canvas.
  SPDD explicitly forbids this.

### 3. REASONS section quality (for new/changed Canvases)
Each section must be substantive — method signatures, parameter
types, explicit invariants — not vague prose.
- **R · Requirements** — concrete Definition of Done.
- **E · Entities** — domain entities with relationships and invariants.
- **A · Approach** — chosen strategy with reasoning and trade-offs.
- **S · Structure** — components, dependencies, signatures with types.
- **O · Operations** — testable, numbered steps a generator could follow.
- **N · Norms** — naming, observability, error handling.
- **S · Safeguards** — non-negotiable invariants, security, performance.

If `N` says "use structured logging" but the diff has `println`, that
is a review comment.

### 4. Story / Analysis / Canvas alignment
Walk the chain backwards:
- Does the Canvas's `R · Requirements` cover every acceptance
  criterion in the story?
- Did the analysis's risks and edge cases make it into `S · Safeguards`?
- Are the analysis's trade-offs reflected in `A · Approach`?

### 5. Drift signals
- **`last_generated_at` frontmatter**: should be updated when code
  is regenerated. Canvas change without a refreshed timestamp
  suggests the Canvas was edited but `/spdd-generate` was not run.
- **`/spdd-sync` diff size**: a larger-than-expected sync diff is a
  yellow flag — the code drifted further than `sync` should silently
  absorb. Ask whether `/spdd-prompt-update` should have been used.
- **Stale `R/E/A` on a sync PR** — those should never be touched by
  `/spdd-sync`.

### 6. Tests
- The `O · Operations` should map to test cases. Run or check
  `scripts/test-api.sh` from `/spdd-api-test` — happy path,
  boundaries, error cases.
- A test failure has a specific diagnosis: **spec gap** (update
  Canvas via `/spdd-prompt-update`) vs **implementation drift**
  (fix code, or `/spdd-sync` if intentional). Confirm the author
  classified it correctly.
- New Operations should come with new tests; missing tests = spec is
  under-specified or generation was incomplete.

### 7. Safeguards & Norms enforcement
Read the Canvas's `N` and `S` sections, then audit the diff against
them:
- Invariants asserted? Validation at boundaries?
- Security: authn/authz on new endpoints, input sanitation, secrets.
- Performance: complexity bounds, query patterns, caching as the
  Canvas dictates.
- Error handling and observability per `N`.

### 8. Ordinary code review still applies
SPDD does not replace normal review — readability, naming, idiomatic
style, security, concurrency, error paths, test coverage. It adds
the spec-conformance layer on top.

## Output

Post a review summary with two sections:

```
## SPDD conformance
- [ ] PR touches the right artifacts (story / Canvas / code as appropriate)
- [ ] Every code change traces to a Canvas line; no scope creep
- [ ] REASONS sections substantive (signatures, invariants, not prose)
- [ ] Story acceptance criteria covered by Canvas R · Requirements
- [ ] N · Norms and S · Safeguards honoured in the diff
- [ ] last_generated_at refreshed if code was regenerated
- [ ] Tests cover each new/changed Operation

## Code review
- <inline comments by file:line>
```

For each unchecked box, leave a specific comment with file/line and
the recommended next step (typically `/spdd-prompt-update`,
`/spdd-sync`, or a code fix).

## After reviewing
If the diff reveals a spec gap, recommend `/spdd-prompt-update` so
the Canvas is fixed before any code change. If it reveals
implementation drift on an otherwise correct spec, recommend
`/spdd-sync` (structure/naming only) or a direct code fix
(behaviour). Reserve approval for PRs where the Canvas and code
agree.
