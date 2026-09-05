# CLAUDE.md

Behavior rules for Claude Code in this repo. **Architecture, package structure, runtime environments, naming conventions, and the openspec conventions all live in `openspec/project.md`** — do not duplicate them here. This file only governs Claude's behavior, workflow, Hard Rules, and common commands. If the architecture isn't clear before you start, read `openspec/project.md` first.

---

## Session Start Checklist

At the start of every new session:

1. Ensure `tasks/lessons.md` and `tasks/todo.md` exist. If missing, create each with a title line + a one-line subtitle.
2. Read `tasks/lessons.md` — known pitfalls accumulated from past corrections.
3. Read `tasks/todo.md` — pending cross-change items and deferred features.
4. Read `openspec/project.md` — project purpose, repo structure, tech stack, and the guide to `openspec/project/`.
5. If working on a feature: check `openspec/changes/` for any active (non-archived) change and read its `tasks.md`.

---

## Critical Rules

- **Never run `git commit` / `git push` on your own** unless explicitly asked. Provide the commands for the user to run manually.
- **Do not over-engineer**: implement only what's asked — no extra endpoints, entities, or config. When in doubt, do less.
- **Reuse before creating**: search `src/main/java/com/alantsai/ticketrush/` for an existing policy / port / adapter / mapper before writing a new one.
- **Verify the schema before modifying queries**: check `src/main/resources/db/migration/` before assuming a column exists.
- **This is a learning project.** Half its purpose is for the user to learn Java and concurrency. See "AI Development Workflow" — code alone is not a deliverable here.

---

## Hard Rules

> Scannable red-line list. Each rule is tagged with **how it is enforced** — `編譯` (compiler),
> `測試` (ArchUnit / integration test), `lint` (spotless / checkstyle), or `自律` (convention
> only, nothing catches it). The `自律` ones are where your attention actually matters.

- 🚫 **Never put a framework annotation in `domain/`** — no Spring, JPA, or Jackson annotations. The domain must know nothing about which lock strategy is in use; that is what makes "four strategies, zero domain changes" true. 〔**測試**〕
- 🚫 **Never inject `PurchaseTicketUseCase` directly** — four implementations exist, so a single injection point throws `NoUniqueBeanDefinitionException` at startup. Access is only through `PurchaseFacade`'s `Map<String, PurchaseTicketUseCase>`. 〔**測試**〕
- 🚫 **Never put `@Transactional` on a controller or a repository** — it belongs on application services only. On a controller it drags HTTP handling into the transaction; on a repository it cannot span multiple repositories atomically. 〔**測試**〕
- 🚫 **Never call a `@Transactional` method from inside the same class** — self-invocation bypasses the AOP proxy and the transaction silently does not apply. This is the single most common Spring mistake and **no test catches it**. 〔**自律**〕
- 🚫 **Never let a JPA entity leak out of `adapter/out/persistence/`** — domain models and `XxxJpaEntity` are separate types joined by a mapper. 〔**測試**〕
- 🚫 **Never use `@CrossOrigin` on a controller** — CORS is configured once in `WebConfig`. 〔**測試**〕
- 🚫 **Never use H2 in tests** — H2's locking behaviour differs from PostgreSQL, so green tests would coexist with an overselling production system. All integration tests run against Testcontainers with `postgres:17` / `redis:7`, matching `~/dev-databases`. 〔**測試**〕
- 🚫 **Never run concurrency tests against the shared `~/dev-databases`** — its `max_connections` is the default 100; the pessimistic-lock strategy will exhaust it and take down every other project on port 5432. Concurrency always goes through Testcontainers. 〔**自律**〕
- 🚫 **Never modify a Flyway migration that has already been applied** — the checksum will mismatch and startup fails. Add a new migration instead. 〔**自律**〕
- 🚫 **Never report a performance number without its measurement conditions** — strategy, thread model (platform / virtual), container CPU and memory limits, `max_connections`, k6 VU count and duration. A number without conditions cannot be compared to another, which makes it worthless in a project whose entire output is comparison. 〔**自律**〕
- 🚫 **Never delete or "fix" the overselling test in the no-lock strategy** — it is meant to be red. It is the evidence that the problem is real, and it is the opening figure of the README. 〔**自律**〕
- 🚫 **Never run `./mvnw spring-boot:run` on your own** — the user starts the app for verification. 〔**自律**〕
- 🚫 **Never modify `.env`** — that's the user's local config; only edit `.env.example`. 〔**自律**〕

---

## Communication Style

- Default reply language is **Traditional Chinese**; switch to English only when the user does.
- When the user says 「不用」 or interrupts, stop immediately and keep replies brief.
- Before a change touching 3+ files, outline the plan (which files, what changes) and wait for confirmation.
- When a requirement is ambiguous, ask one key question rather than guessing the implementation.
- Match reply length to question complexity. Simple question → direct answer, no headers.

---

## Documentation Languages

| File / location            | Language                 |
| -------------------------- | ------------------------ |
| `CLAUDE.md` (this file)    | **English**              |
| `README.md`                | Traditional Chinese      |
| `openspec/project.md`      | Traditional Chinese      |
| `openspec/project/**/*.md` | Traditional Chinese      |
| `openspec/changes/**/*.md` | Traditional Chinese      |
| `openspec/specs/**/*.md`   | Traditional Chinese      |
| `tasks/*.md`               | Traditional Chinese      |
| Code comments (all files)  | Traditional Chinese only |

- **Never use Japanese** in any artifact.
- **Never write code comments in English or bilingual** — Traditional Chinese only.
- **The `##` headings in openspec artifacts stay English** — `## Why`, `## What Changes`, `## Capabilities`, `## Impact`, `## Context`, `## Decisions`, `## ADDED Requirements`, `### Requirement:`, `#### Scenario:` are parsed by the openspec CLI. Translating them breaks parsing silently. Everything under those headings is Traditional Chinese.

---

## Code Style

- Every non-trivial method gets a Traditional-Chinese Javadoc comment describing **what it does and why it exists**, written for callers.
- Inline comments explain **why** (non-obvious reasoning, gotchas, lock semantics), not what.
- **No `Impl` suffix** — `PessimisticPurchaseService`, not `PurchaseUseCaseImpl`. The four strategies are distinguished by name; a meaningless suffix defeats that.
- **No `I` prefix on interfaces** — that is a .NET convention. Write `StockRepositoryPort`.
- Prefer constructor injection (final fields), never field injection.
- Use `record` for commands, DTOs, and value objects where mutation is not needed.

---

## AI Development Workflow

Three layers work together:

| Layer       | Tool                                 | Purpose                                                      |
| ----------- | ------------------------------------ | ------------------------------------------------------------ |
| **Memory**  | `tasks/todo.md` + `tasks/lessons.md` | Cross-session deferred items and lessons                     |
| **Spec**    | `openspec/changes/<name>/`           | Per-change proposal / design / specs / tasks                 |
| **Process** | openspec + selected superpowers      | Change management + TDD / verification / debugging discipline |

### The learning constraint — read this before writing code

The user has chosen **AI writes, user reviews**. That is a deliberate trade: it gets the load-test data faster, at the cost of hands-on practice. To keep the review from becoming a rubber stamp, three things are **mandatory**:

1. **Every implementation ships with a decision note, not just code.** Why this isolation level, why the transaction boundary sits here, which alternatives were rejected and at what cost. Without this there is nothing to review — a diff of correct-looking Java teaches nothing.
2. **Run `grill-me` after each strategy layer is complete.** It verifies the user can explain what is in their own repo. Discovering the gap in an interview is too late.
3. **Record "I assumed X, it was actually Y" in `tasks/lessons.md`.** For this project that file is the evidence of the learning curve, not just a pitfall list.

The most valuable part of this project is **interpreting the data**, not producing the code. When a number looks surprising, do not smooth it over — investigate and explain it. That analysis is the part no one else can do for the user, because the data came from their machine.

### Change lifecycle — the exact order

This is the user's established flow. Follow it as written; do not reorder or skip steps.

1. **Branch first** — cut from `develop`. This happens *before* the change folder exists. Branch name is `<type>/<full change name>`, **keeping the change's verb prefix** so the branch, the change folder, and the archived folder all read the same and need no mental translation:

   | change prefix | branch type |
   | --- | --- |
   | `add-` / `improve-` | `feat/` |
   | `fix-` | `fix/` |
   | `refactor-` | `refactor/` |
   | `enforce-` | `chore/` |

   Example: change `add-project-skeleton` → branch `feat/add-project-skeleton`.
2. **Create the change** — `openspec new change "<name>" --schema spec-driven-custom`, then design → proposal → specs → tasks. **The user approves before any code is written.**
3. **Mark it in progress** — record the change name and goal in `tasks/todo.md`.
4. **Implement** — block by block (see Phase 3). Each block: Pre-Change Checklist green → hand the user one commit command → next block.
5. **Archive** — `openspec-archive-change`: merge the change's `specs/` into `openspec/specs/`, move the folder to `openspec/changes/archive/<YYYY-MM-DD>-<name>/`.
6. **Write lessons** — append to `tasks/lessons.md` **only if a real pitfall was hit**. Nothing hit, nothing written; padding this file destroys its value.
7. **Update todo** — tick the change off, move any deferred items into the deferred section.
8. **Wrap-up commit** — archive + lessons + todo in one commit.
9. **Push** — the user pushes the branch.
10. **Merge on GitHub** — PR into `develop`.

Steps 4 and 8 both produce commits: step 4 is **per-block during implementation**, step 8 is a **single wrap-up commit**. Never run `git commit` / `git push` yourself at either — always hand the command to the user.

### Phase 1 — Explore & Design

- Use `openspec-explore` (or `superpowers:brainstorming` — one question at a time, decisions via `AskUserQuestion`) to clarify requirements.
- Write the approved design to `openspec/changes/<name>/design.md`.

### Phase 2 — Specify

- Use `openspec-propose` → generates `proposal.md`, `specs/`, `tasks.md`.
- **Changes must be created with `--schema spec-driven-custom`.** A missing flag silently falls back to the built-in schema and every project rule stops applying.
- Capability names carry a mandatory prefix: `api-`, `strategy-`, `platform-`, `ui-`. The prefix dictates how the spec is written — see `openspec/project/openspec-conventions.md`.
- `strategy-*` specs must define correctness acceptance, performance metrics, and a comparison against the previous layer. Acceptance conditions must be falsifiable.
- For backend changes, `tasks.md` phases follow: Migration → Domain/Port → Application Service (TDD) → Out Adapter → Controller/DTO → Facade wiring → ArchUnit guardrail → Integration tests → Load test → Verification → Wrap-up.
- The user reviews and approves before any code is written.

### Phase 3 — Implement

- Use `openspec-apply` to work task by task.
- For service / use case implementation use `superpowers:test-driven-development`.
- **Work in blocks**: each block must build and verify independently. Never leave a non-compiling intermediate state. Each block: run the Pre-Change Checklist green → give one commit command (the user runs it) → next block.
- Before marking a task done, use `superpowers:verification-before-completion` — never claim "done" without running the verification command and showing its output.
- **Concurrency tests need reverse verification.** A concurrency test can be permanently green for the wrong reason (e.g. the threads never actually started simultaneously). Deliberately break the production code, watch it turn red, restore, confirm `git status` is clean.

### Phase 4 — Complete

- Use `openspec-archive-change` — merges the change's `specs/` into `openspec/specs/` and moves the folder to `openspec/changes/archive/<YYYY-MM-DD>-<name>/`.
- Move deferred items to `tasks/todo.md`; append new lessons to `tasks/lessons.md`.
- Debug at any phase with `superpowers:systematic-debugging` — find the root cause before fixing.

### Memory rules

**`tasks/todo.md`** — update when: starting a change (record name + goal), finishing one (verify goals met, move to done), discovering a cross-change side effect (write it immediately), or deferring a feature (record the reason and the condition).

**`tasks/lessons.md`** — append after corrections OR after the user confirms a non-obvious approach worked. **Only real pitfalls belong here.** Three things do not: restatements of official docs (delete), project conventions and architecture decisions (move to `openspec/project/` — move first, then delete), and rules already enforced by a guardrail (delete — if a machine catches it, nobody needs to remember it). Prune periodically.

---

## Pre-Change Checklist

After making changes, before suggesting a commit:

1. `./mvnw compile` — fix all compilation errors.
2. `./mvnw spotless:check` — formatting (`spotless:apply` to fix).
3. `./mvnw test` — unit tests **plus the ArchUnit guardrails**.
4. `./mvnw verify` — adds the Testcontainers integration tests. Required whenever persistence, locking, or transaction boundaries changed.
5. If a strategy layer changed: run its k6 scenario and record the numbers **with their measurement conditions** into the change's spec.

Once all pass, suggest a commit message (Traditional Chinese, conventional commits; body as bullets, one change per bullet). Do not run `git commit` yourself.

---

## Commands

```bash
./mvnw spring-boot:run                        # start the app (user runs this; don't run it yourself)
./mvnw compile && ./mvnw test                 # fast loop: compile + unit tests + ArchUnit
./mvnw verify                                 # adds Testcontainers integration tests
docker compose --profile perf up --build      # load-test environment (app + pg + redis + k6, no host ports)
docker exec my-postgres createdb -U postgres ticket_rush_db   # one-time dev DB setup
```

Development uses the shared `~/dev-databases` (PostgreSQL 17 on 5432, Redis 7 on 6379) — this repo does not start its own dev containers. Details in `openspec/project/backend-runtime.md`.

---

## Architecture & Conventions

`openspec/project.md` is the **index** — purpose, repo structure, tech stack, and a table pointing into `openspec/project/`. Read the index first, then open only the file you need:

| File | Covers |
| --- | --- |
| `project/backend-architecture.md` | Hexagonal layout, package structure, why the four strategies live at the in-port, strategy switching, domain/entity separation, naming, the five architectural constraints |
| `project/backend-runtime.md` | Dev / test / load-test environments, when the shared DB is usable, load-test configuration, JVM container resource awareness, Dockerfile, data credibility statement |
| `project/openspec-conventions.md` | Custom schema, capability prefixes (including this project's `strategy-`), `strategy-*` acceptance format, change naming, tasks.md block splitting, writing quality bar |

Don't duplicate any of that here. When in doubt, read `openspec/project.md` first.
