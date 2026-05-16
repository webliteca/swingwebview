# Project guidance

## Structured Prompt-Driven Development (SPDD)

This repository uses Structured Prompt-Driven Development (SPDD). Canvases (REASONS files) under `spdd/prompt/` are the source of truth for behavior. Generated files (code, tests, configuration that implements a Canvas) live under `requirements/`, `spdd/analysis/`, `spdd/prompt/`, **and anywhere a Canvas's REASONS-Implements section points** — typically `src/`. If a source file is produced by a Canvas, it is generated, even though it lives outside `spdd/`.

## Hard rules — read before editing any file

Hand-editing rules depend on whether the change touches observable behavior:

1. **For behavior changes, never hand-edit a generated source file.** Use `/spdd-prompt-update` followed by `/spdd-generate` instead — no matter how small the change looks (one line, one file, "trivial" rename of a user-visible string — all still go through the Canvas).
2. **For non-behavior changes (refactor, rename internal symbol, restructure with identical observable behavior), hand-editing is the correct move** — then run `/spdd-sync` in the same turn so the Canvas's Structure/Operations/Norms sections reflect the new shape of the code.
3. **Never skip `/spdd-generate` after `/spdd-prompt-update`.** They are one operation in two steps. A turn that updates a Canvas without regenerating leaves the repo in a broken, half-applied state.
4. **Never skip `/spdd-sync` after a non-behavior code edit.** Same drift problem in reverse: code moved, Canvas didn't.

## Anti-rationalization checklist

Before you reach for `Edit` or `Write` on a source file, stop and answer these out loud:

- Is this file referenced by a Canvas under `spdd/prompt/`?   If yes, it is generated. Treat it as read-only output.
- Am I about to argue that `/spdd-generate` is "overkill"   because only one or two files need to change? **That   reasoning is wrong and is the single most common way   this workflow gets broken.** `/spdd-generate` is   idempotent and file-scoped: it diffs each target against   what the Canvas implies and only rewrites files that   actually need to change. Running it on a one-line fix   costs you nothing and produces the same one-line diff a   hand-edit would — plus it keeps the Canvas as the source   of truth. There is no scenario where skipping it because   "only a few files are affected" is correct.
- Am I about to argue that hand-editing is "faster" or   "more surgical"? Also wrong. The slash commands exist   precisely so you don't have to make that judgement call.   Run them.
- Am I about to argue that the Canvas change is "obvious"   and the code change is "mechanical", so I can just do   both manually? Wrong again — the whole point of   `/spdd-generate` is that the mapping from Canvas to code   is mechanical, so let the tool do it.

If any of those rationalizations crossed your mind, you **must** use the slash commands. Treat the urge to hand-edit as a signal that you are about to violate the workflow.

## Decision framework

Ask: "does this change observable behavior?"

- **Yes — behavior/logic change** (bug in requirements, new business rule, changed user-visible behavior, changed API contract, changed error message a user sees):   1. Run `/spdd-prompt-update` to amend the Canvas.   2. In the **same turn**, run `/spdd-generate` to push      the change into code.   No exceptions. If the change is large enough to warrant   re-deriving the story, run `/spdd-story` and   `/spdd-analysis` first, but `/spdd-generate` is still   the step that touches code.
- **No — non-behavior change** (refactor, rename internal symbol, reformat, restructure with identical observable behavior):   1. Edit the code directly.   2. In the **same turn**, run `/spdd-sync` to reconcile      the Canvas with the new code shape.

If you are unsure which bucket a change falls into, treat it as a behavior change and go through `/spdd-prompt-update` + `/spdd-generate`. The cost of using the workflow when you didn't strictly need to is zero; the cost of skipping it when you needed it is a drifted Canvas.

These pairings are non-optional. The Canvas and the code must land in a consistent state in **every** commit; the slash commands are the only mechanism that guarantees this. If `/spdd-generate` would touch more than the change warrants, still invoke it, then narrow the diff or check with the user — do not bypass it.

## Lifecycle for new work

`/spdd-story` → `/spdd-analysis` → `/spdd-reasons-canvas` → `/spdd-generate` → `/spdd-api-test`

When planning work, identify Canvases under `spdd/prompt/` that are affected, populate the task's references with their paths, and lay out which SPDD operations to perform **before** you start editing anything.
