# Rank Rewards Production Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship useful rank privileges, weekly CMI-backed rewards, NPC-contract rank modifiers, polished promotion effects, and the new menu entry point as an owner-only production canary.

**Architecture:** ArcRanks keeps cross-server weekly claim state in its existing MySQL runtime and invokes native CMI kits through a narrow provider after a durable reservation. ARC applies pure, bounded LuckPerms numerical policies to its existing contract state machine. LuckPerms, Lands, zAuctionHouse and zMenu remain configuration-owned surfaces.

**Tech Stack:** Kotlin 2.3, Paper 1.21.11, Java 25, arc-core 2.1.3/2.2.0, MySQL, LuckPerms, CMI, ARC MCP, Kotest.

**Spec:** `docs/superpowers/specs/2026-08-30-rank-rewards-production-design.md`

## Global Constraints

- Interesting gameplay stays available at the base rank; ranks improve scale, rewards and presentation.
- CMI kits are written only through ARC's typed CMI API and are read back after each production mutation.
- Weekly claims are shared across servers and never blindly replay an ambiguous item delivery.
- GUI inventories are five rows, symmetric, warm, non-italic, use the shared background, have one back arrow in submenus and no close barrier.
- Production activation is owner-only; no global legacy-rank migration or deletion belongs to this rollout.

---

### Task 1: Weekly-kit domain and persistence

**Files:**
- Create: `src/main/kotlin/ru/ruscrafting/ranks/kit/WeeklyKitDomain.kt`
- Create: `src/main/kotlin/ru/ruscrafting/ranks/kit/WeeklyKitRepository.kt`
- Create: `src/main/kotlin/ru/ruscrafting/ranks/kit/MySqlWeeklyKitRepository.kt`
- Modify: `src/main/kotlin/ru/ruscrafting/ranks/storage/RankMigrations.kt`
- Test: `src/test/kotlin/ru/ruscrafting/ranks/kit/WeeklyKitServiceTest.kt`
- Test: `src/integrationTest/kotlin/ru/ruscrafting/ranks/kit/MySqlWeeklyKitRepositoryIntegrationTest.kt`

**Interfaces:**
- Produces: `WeeklyKitCycle.at(Instant): LocalDate`, `WeeklyKitRepository.begin(...)`, `confirm(...)`, `release(...)`, and `WeeklyKitService.claim(...)`.
- Guarantees: duplicate claims return `AlreadyClaimed`; an existing `DELIVERING` row returns `DeliveryPending`; provider refusal releases only its matching claim id.

- [ ] **Step 1: Write failing service tests** for Moscow Monday boundaries, duplicate weekly claims, inventory rejection, provider refusal release, successful confirmation and ambiguous confirmation failure.
- [ ] **Step 2: Run** `./gradlew --no-daemon test --tests '*WeeklyKitServiceTest'` and verify failures are caused by missing kit types.
- [ ] **Step 3: Implement** immutable kit definitions, claim results and a service that bridges SQL work to a sync-only provider without placing Bukkit work on the SQL executor.
- [ ] **Step 4: Add migration 6** with `(player_uuid, cycle_start)` uniqueness, a unique `claim_id`, `DELIVERING|CLAIMED` state and server/rank/kit audit fields.
- [ ] **Step 5: Run the focused unit test** and compile the integration source with `./gradlew --no-daemon test compileIntegrationTestKotlin`.

### Task 2: Kit configuration, menu and telemetry

**Files:**
- Create: `src/main/resources/weekly-kits.yml`
- Create: `src/main/kotlin/ru/ruscrafting/ranks/kit/WeeklyKitCatalog.kt`
- Create: `src/main/kotlin/ru/ruscrafting/ranks/gui/WeeklyKitMenu.kt`
- Modify: `src/main/kotlin/ru/ruscrafting/ranks/gui/RankPassportMenu.kt`
- Modify: `src/main/kotlin/ru/ruscrafting/ranks/paper/ArcRanksPlugin.kt`
- Modify: `src/main/kotlin/ru/ruscrafting/ranks/command/RankCommand.kt`
- Modify: `src/main/kotlin/ru/ruscrafting/ranks/analytics/ProductTelemetry.kt`
- Modify: `src/main/kotlin/ru/ruscrafting/ranks/text/RankLocale.kt`
- Modify: `src/main/resources/lang/ru.yml`
- Modify: `src/main/resources/lang/en.yml`
- Test: `src/test/kotlin/ru/ruscrafting/ranks/kit/WeeklyKitCatalogTest.kt`
- Test: `src/test/kotlin/ru/ruscrafting/ranks/gui/RankPassportContractTest.kt`
- Test: `src/test/kotlin/ru/ruscrafting/ranks/gui/RankMenuVisualContractTest.kt`

**Interfaces:**
- Consumes: Task 1's `WeeklyKitService`.
- Produces: `/rank kit`, `WeeklyKitMenu.open(Player)`, `ProductEvent.WEEKLY_KIT_*` counters and the symmetric passport slots `30/31/32`.

- [ ] **Step 1: Add failing catalog and GUI contract tests** asserting nine safe CMI ids, rank parity, five-row symmetry, no close item, one back item, no literal `LF`, and every visible lore line has `<italic:false>`.
- [ ] **Step 2: Run the focused tests** and verify the new kit/menu assertions fail.
- [ ] **Step 3: Implement** catalog loading, CMI console provider `cmi kit <id> <player> -s`, preview/claim menu, passport cards, command routing and bounded telemetry dimensions.
- [ ] **Step 4: Add exact Russian and English copy** for kit state, contents, privileges, failure/retry behavior and commands; validate exact locale-key parity.
- [ ] **Step 5: Run** `./gradlew --no-daemon test --tests '*WeeklyKit*' --tests '*RankPassport*' --tests '*RankMenuVisual*' --tests '*RankLocale*'`.

### Task 3: Rank groups and privilege copy

**Files:**
- Modify: `src/main/resources/ranks.yml`
- Modify: `src/main/resources/lang/ru.yml`
- Modify: `src/main/resources/lang/en.yml`
- Test: `src/test/kotlin/ru/ruscrafting/ranks/config/RankCatalogLoaderTest.kt`

**Interfaces:**
- Produces: progression groups `default`, `rank_peasant` through `rank_caesar` and exact benefit lore matching the approved jobs, land, home, auction, warp, contract and weekly-kit values.

- [ ] **Step 1: Add a failing catalog test** for the nine exact new groups and privilege benefit-key counts.
- [ ] **Step 2: Run the focused catalog test** and observe the legacy-group mismatch.
- [ ] **Step 3: Replace group ids and generic benefit copy** with concrete player-facing privileges in both locales.
- [ ] **Step 4: Run catalog and locale tests** and verify parity.

### Task 4: Bounded promotion presentation

**Files:**
- Create: `src/main/kotlin/ru/ruscrafting/ranks/presentation/PromotionCelebration.kt`
- Modify: `src/main/kotlin/ru/ruscrafting/ranks/paper/ArcRanksPlugin.kt`
- Modify: `src/main/resources/lang/ru.yml`
- Modify: `src/main/resources/lang/en.yml`
- Test: `src/test/kotlin/ru/ruscrafting/ranks/presentation/PromotionCelebrationProfileTest.kt`

**Interfaces:**
- Produces: `PromotionCelebration.celebrate(UUID, RankId)` and an event listener that cancels damage from fireworks tagged `arcranks_visual`.
- Guarantees: at most 36 particles and two fireworks per promotion; only ranks 7-9 invoke one `/x -servers:all cmi broadcast ...` command.

- [ ] **Step 1: Write failing pure profile tests** for low/middle/high intensity and broadcast threshold.
- [ ] **Step 2: Run the focused test** and verify missing profile behavior.
- [ ] **Step 3: Implement** warm dust/end-rod particles, tagged fireworks, damage cancellation, title/sound and network announcement with local fallback.
- [ ] **Step 4: Run the presentation and plugin descriptor tests**.

### Task 5: ARC NPC-contract rank policy

**Files (sibling `../ARC`):**
- Create: `src/main/kotlin/ru/arc/contracts/ContractRankPolicy.kt`
- Modify: `src/main/kotlin/ru/arc/contracts/ContractDomain.kt`
- Modify: `src/main/kotlin/ru/arc/contracts/ContractSubmissionCoordinator.kt`
- Modify: `src/main/kotlin/ru/arc/contracts/ContractSubmissionJournal.kt`
- Modify: `src/main/kotlin/ru/arc/contracts/ContractsModule.kt`
- Modify: `src/main/kotlin/ru/arc/contracts/ContractQuantitySelection.kt`
- Modify: `src/main/kotlin/ru/arc/contracts/NpcContractsGui.kt`
- Test: `src/test/kotlin/ru/arc/contracts/ContractRankPolicyTest.kt`
- Test: `src/test/kotlin/ru/arc/contracts/ContractDomainTest.kt`
- Test: `src/test/kotlin/ru/arc/contracts/ContractSubmissionJournalTest.kt`

**Interfaces:**
- Produces: numerical permissions `arc.contracts.player-cap-percent.<100..200>` and `arc.contracts.payout-percent.<100..125>` resolved to the highest true value.
- Guarantees: actual boosted payouts are journaled and consume the existing server budget; stored quantities and payouts remain bounded by the maximum policy.

- [ ] **Step 1: Add failing policy/domain tests** for 150% personal cap, 112% payout, budget exhaustion at the boosted rate, replay, reservation validation and invalid permission bounds.
- [ ] **Step 2: Run the focused ARC tests** and verify they fail on missing policy support.
- [ ] **Step 3: Thread one immutable policy** from the online player through view, selection, submission planning, journal and recovery validation; preserve default 100% behavior.
- [ ] **Step 4: Show effective cap, rate and rank bonus** in both NPC list and detail lore.
- [ ] **Step 5: Run** `./gradlew --no-daemon test --tests 'ru.arc.contracts.*' shadowJar` in `../ARC`.

### Task 6: Production-owned configuration

**Files (fresh trunk worktree of `../ruscrafting-ops`):**
- Modify: `luckperms.yml`
- Modify: `classic_survival/plugins/Lands/player-limits.yml`
- Modify: `classic/plugins/zAuctionHouse/config.yml`
- Modify: `classic_survival/plugins/zAuctionHouse/config.yml`
- Modify: both zMenu profile dialog/menu entry files that currently route to the legacy rank screen.
- Modify: both ArcRanks runtime profiles after the plugin deploy creates them.

**Interfaces:**
- Produces: nine independent progression groups, exact numerical permissions, nine Lands packs ordered highest-first, auction limits `5/7/10/12/15/20/25/30/40`, and a permission-gated `/rank` entry point.

- [ ] **Step 1: Add or update validation fixtures** so exact rank values and both-node parity are machine checked.
- [ ] **Step 2: Edit only the declared config paths** in a freshly fetched detached trunk worktree; preserve every unrelated dirty file in the shared checkout.
- [ ] **Step 3: Run** `./scripts/mc validate`, `./scripts/mc permissions review`, and scoped deploy dry-runs; inspect the exact review for removals.
- [ ] **Step 4: Commit and push the scoped ops diff** only after the ArcRanks and ARC source commits are pushed.

### Task 7: Native CMI kits and release verification

**Runtime mutations:**
- Create or replace nine `arcranks_weekly_<rank>` CMI kits on `classic` and `classic_survival` through `arc_ops_content_read(preview)` then `arc_ops_content_write(upsert)`.
- Pull the two generated CMI runtime snapshots into the ops repository through the supported runtime workflow.

**Verification:**
- [ ] **Step 1: Preview all nine payloads on both nodes** and reject any unknown preset, unsafe command or schema mismatch.
- [ ] **Step 2: Write each kit separately to each node**, read it back, and run `arc_ops_content_health` without granting a kit to the owner's inventory.
- [ ] **Step 3: Run ArcRanks' required gate** `./gradlew --no-daemon test compileIntegrationTestKotlin shadowJar`, the arc-core consumer verifier and `scripts/render-visual-preview`.
- [ ] **Step 4: Commit/push both source trunks, wait for exact CI, record JAR SHA-256 values, then commit/push the scoped ops trunk.**
- [ ] **Step 5: Deploy ARC and ArcRanks with routine restarts**, sync the approved LuckPerms review, deploy the scoped configs, and keep player access owner-only.
- [ ] **Step 6: Verify new PIDs, readiness, active JAR hashes, ArcRanks/ARC health, MySQL migrations, kit readback, owner effective permissions, `/rank` opening, recent WARN/ERROR logs and rendered GUI screenshots.**

