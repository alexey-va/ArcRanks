# ArcRanks Engagement Systems Implementation Plan

**Goal:** Extend ArcRanks with a lightweight passport hub, durable product analytics, weekly personal contracts, and freely switchable specialization perks.

**Architecture:** Gameplay paths only update bounded memory buffers and cached perk state. Arc-core SQL executors periodically persist telemetry rollups and own transactional contract/perk state; GUI callbacks return through the lifecycle task scope. Four focused menus share one component factory and locale catalog.

**Tech Stack:** Kotlin 2.3.0, Java 25, Paper 1.21.11, arc-core 2.1.3, LuckPerms 5.5, MySQL 8.0, Kotest 6.0.7, MockK 1.14.7.

**Spec:** `docs/superpowers/specs/2026-08-29-passport-contracts-perks-analytics-design.md`

## Global Constraints

- Keep direct trunk `main`; do not create a feature branch.
- Never call SQL, Vault, LuckPerms, logging, or Prometheus from a gameplay event path.
- Every SQL operation runs through the existing `SqlRuntime` executor.
- Keep telemetry dimensions bounded and exclude player names/UUIDs from Prometheus labels.
- Keep two perk slots available to every player; mastery unlocks enhancements, not public mechanics.
- Keep contract rewards idempotent and atomic with claim state.
- Keep RU/EN exact leaf parity and explicit non-italic item roots.
- Keep source GUI materials vanilla and runtime resource-pack overrides configurable.
- Never run local `integrationTest`, Testcontainers, or Docker.
- Do not deploy, restart, rebalance permissions, or create/push a new remote repository.

---

### Task 1: Add bounded product telemetry

**Files:**
- Create: `src/main/kotlin/ru/ruscrafting/ranks/analytics/ProductTelemetry.kt`
- Create: `src/main/kotlin/ru/ruscrafting/ranks/analytics/AnalyticsRepository.kt`
- Create: `src/main/kotlin/ru/ruscrafting/ranks/analytics/MySqlAnalyticsRepository.kt`
- Create: `src/main/kotlin/ru/ruscrafting/ranks/analytics/AnalyticsService.kt`
- Modify: `src/main/kotlin/ru/ruscrafting/ranks/storage/RankMigrations.kt`
- Modify: `src/main/kotlin/ru/ruscrafting/ranks/config/ArcRanksSettings.kt`
- Modify: `src/main/resources/config.yml`
- Test: `src/test/kotlin/ru/ruscrafting/ranks/analytics/ProductTelemetryTest.kt`
- Test: `src/test/kotlin/ru/ruscrafting/ranks/analytics/AnalyticsServiceTest.kt`
- Integration: `src/integrationTest/kotlin/ru/ruscrafting/ranks/analytics/MySqlAnalyticsRepositoryIntegrationTest.kt`

**Interfaces:**
- Produces: `ProductEvent`, `ProductDimension`, `PlayerSignal`, `ProductTelemetry.record(event, dimension, amount)`, `recordPlayer(playerId, signal, rankId)`, `flush()`, `healthSnapshot()`.
- Produces: `AnalyticsRepository.write(batch)`, `summary(days)`, and `AnalyticsSummary`.

- [ ] **Step 1: Write telemetry RED tests**

Test that records do not invoke the writer, duplicate keys coalesce, player
signals merge, capacity rejects only a new key, a failed flush restores the
exact batch, and a successful flush empties pending state.

- [ ] **Step 2: Observe telemetry RED**

Run: `./gradlew --no-daemon test --tests '*ProductTelemetryTest'`

Expected: compilation fails because the analytics types do not exist.

- [ ] **Step 3: Implement the bounded buffer**

Use validated enum-backed keys, synchronized ownership, one in-flight future,
failure restoration, and cached health counters. `record` performs no future
creation and no external call.

- [ ] **Step 4: Write summary RED tests**

Use a real in-memory repository fake and literal totals to prove conversion
rates, zero-denominator handling, day-window validation (`7`, `14`, `30`), and
60-second cache reuse.

- [ ] **Step 5: Implement repository, migrations, and summary service**

Add migration version 3 for hourly rollups and daily player flags. Use batch
prepared statements and additive/upsert SQL. Keep the admin query asynchronous.

- [ ] **Step 6: Add the MySQL integration contract**

Cover migration idempotence, two additive rollup writes, merged daily flags,
window filtering, and no UUID/string exposure in rollup dimensions.

- [ ] **Step 7: Run local analytics GREEN gates**

Run: `./gradlew --no-daemon test --tests '*ProductTelemetryTest' --tests '*AnalyticsServiceTest' compileIntegrationTestKotlin`

Expected: unit tests pass and integration sources compile without running Docker.

### Task 2: Load and validate the perk catalog

**Files:**
- Create: `src/main/kotlin/ru/ruscrafting/ranks/perk/PerkCatalog.kt`
- Create: `src/main/kotlin/ru/ruscrafting/ranks/perk/PerkCatalogLoader.kt`
- Create: `src/main/resources/perks.yml`
- Modify: `src/main/kotlin/ru/ruscrafting/ranks/paper/ArcRanksPlugin.kt`
- Test: `src/test/kotlin/ru/ruscrafting/ranks/perk/PerkCatalogTest.kt`

**Interfaces:**
- Produces: `PerkId`, `PerkEffectKind`, `PerkDefinition`, `PerkCatalog`, and `PerkCatalogLoader.load()`.
- Consumes: `SpecializationPath`, `MasteryLevel`, and locale-key strings.

- [ ] **Step 1: Write perk-catalog RED tests**

Reject duplicate IDs, missing paths, incompatible trade progress bonuses,
mastery `NONE`, unsafe locale keys, non-positive/out-of-range basis points, and
catalogs without exactly two perks per path. Prove bundled defaults load twelve
perks.

- [ ] **Step 2: Observe catalog RED**

Run: `./gradlew --no-daemon test --tests '*PerkCatalogTest'`

- [ ] **Step 3: Implement catalog and bundled perks**

Define one mastery-I progress perk and one mastery-II contract-target perk for
each counter path. Define trade target-reduction and reward-bonus perks.

- [ ] **Step 4: Run catalog GREEN**

Run: `./gradlew --no-daemon test --tests '*PerkCatalogTest'`

### Task 3: Persist perk selection and evaluate effects

**Files:**
- Create: `src/main/kotlin/ru/ruscrafting/ranks/perk/PerkSelectionRepository.kt`
- Create: `src/main/kotlin/ru/ruscrafting/ranks/perk/MySqlPerkSelectionRepository.kt`
- Create: `src/main/kotlin/ru/ruscrafting/ranks/perk/PerkSelectionService.kt`
- Create: `src/main/kotlin/ru/ruscrafting/ranks/perk/FractionalProgressBonus.kt`
- Modify: `src/main/kotlin/ru/ruscrafting/ranks/storage/RankMigrations.kt`
- Modify: `src/main/kotlin/ru/ruscrafting/ranks/service/RankPlayerService.kt`
- Test: `src/test/kotlin/ru/ruscrafting/ranks/perk/PerkSelectionServiceTest.kt`
- Test: `src/test/kotlin/ru/ruscrafting/ranks/perk/FractionalProgressBonusTest.kt`
- Integration: `src/integrationTest/kotlin/ru/ruscrafting/ranks/perk/MySqlPerkSelectionRepositoryIntegrationTest.kt`

**Interfaces:**
- Produces: `PerkSelection`, `PerkSelectionResult`, `select(playerId, perkId, mastery)`, `remove(playerId, perkId)`, and `load(playerId)`.
- Produces: `FractionalProgressBonus.apply(playerId, metric, delta, basisPoints): Long` returning bonus units only.

- [ ] **Step 1: Write perk-selection RED tests**

Prove locked rejection, first/second slot filling, duplicate idempotence, third
slot rejection without mutation, selected removal, and preservation of the
other slot.

- [ ] **Step 2: Observe selection RED**

Run: `./gradlew --no-daemon test --tests '*PerkSelectionServiceTest'`

- [ ] **Step 3: Implement transactional selection**

Add migration version 4. Lock a player-scoped selection row/table range,
validate against the catalog and supplied mastery snapshot, and return typed
outcomes. Refresh the non-blocking player cache after selection changes.

- [ ] **Step 4: Write fractional-bonus RED tests**

Assert ten one-unit events at 10% yield exactly one bonus, a ten-unit event
yields one immediately, player/metric remainders remain isolated, and clearing
a player discards only a sub-unit remainder.

- [ ] **Step 5: Implement fractional bonuses and run GREEN**

Run: `./gradlew --no-daemon test --tests '*PerkSelectionServiceTest' --tests '*FractionalProgressBonusTest' compileIntegrationTestKotlin`

### Task 4: Apply perks without blocking gameplay

**Files:**
- Create: `src/main/kotlin/ru/ruscrafting/ranks/perk/PerkProgressModifier.kt`
- Modify: `src/main/kotlin/ru/ruscrafting/ranks/progress/RankProgressListener.kt`
- Modify: `src/main/kotlin/ru/ruscrafting/ranks/progress/PeriodicProgressSampler.kt`
- Modify: `src/main/kotlin/ru/ruscrafting/ranks/service/PlayerSnapshotListener.kt`
- Test: `src/test/kotlin/ru/ruscrafting/ranks/perk/PerkProgressModifierTest.kt`

**Interfaces:**
- Consumes: cached `RankPlayerSnapshot.activePerks`, `PerkCatalog`, and `ProductTelemetry`.
- Produces: `recordCounter(playerId, metric, baseDelta)` that writes base plus deterministic bonus to `ProgressBuffer` and records only aggregated bonus units.

- [ ] **Step 1: Write modifier RED tests**

Prove no-perk identity, matching-path bonus, unrelated-path isolation,
high-water exclusion, and no repository call during modification.

- [ ] **Step 2: Observe modifier RED**

Run: `./gradlew --no-daemon test --tests '*PerkProgressModifierTest'`

- [ ] **Step 3: Implement and wire the cached modifier**

Route built-in counter events and community sampling through the modifier.
Keep wealth maximum sampling unchanged. Clear fractional remainder on quit.

- [ ] **Step 4: Run modifier and progress suites GREEN**

Run: `./gradlew --no-daemon test --tests '*PerkProgressModifierTest' --tests '*ProgressBufferTest' --tests '*ProgressEventRulesTest'`

### Task 5: Generate deterministic personal contracts

**Files:**
- Create: `src/main/kotlin/ru/ruscrafting/ranks/contract/ContractDomain.kt`
- Create: `src/main/kotlin/ru/ruscrafting/ranks/contract/ContractOfferGenerator.kt`
- Create: `src/main/resources/contracts.yml`
- Create: `src/main/kotlin/ru/ruscrafting/ranks/contract/ContractCatalogLoader.kt`
- Test: `src/test/kotlin/ru/ruscrafting/ranks/contract/ContractOfferGeneratorTest.kt`

**Interfaces:**
- Produces: `ContractCycle`, `ContractOffer`, `ContractCatalog`, and `ContractOfferGenerator.offers(playerId, cycle, generation, rerollNonce, snapshot): List<ContractOffer>`.

- [ ] **Step 1: Write generator RED tests**

Use fixed UUIDs and instants to prove Monday UTC boundaries, stable repeated
offers, three distinct available paths, selected-focus priority, nearest-path
priority, provider exclusion, reroll variation, rank scaling, perk target
reduction, and empty offers after generation three.

- [ ] **Step 2: Observe generator RED**

Run: `./gradlew --no-daemon test --tests '*ContractOfferGeneratorTest'`

- [ ] **Step 3: Implement typed config and deterministic generation**

Use SHA-256 bytes only for stable path rotation. Keep target and reward math in
bounded integer basis points with checked overflow.

- [ ] **Step 4: Run generator GREEN**

Run: `./gradlew --no-daemon test --tests '*ContractOfferGeneratorTest'`

### Task 6: Persist contract cycles and atomic claims

**Files:**
- Create: `src/main/kotlin/ru/ruscrafting/ranks/contract/ContractRepository.kt`
- Create: `src/main/kotlin/ru/ruscrafting/ranks/contract/MySqlContractRepository.kt`
- Create: `src/main/kotlin/ru/ruscrafting/ranks/contract/ContractService.kt`
- Modify: `src/main/kotlin/ru/ruscrafting/ranks/storage/RankMigrations.kt`
- Test: `src/test/kotlin/ru/ruscrafting/ranks/contract/ContractServiceTest.kt`
- Integration: `src/integrationTest/kotlin/ru/ruscrafting/ranks/contract/MySqlContractRepositoryIntegrationTest.kt`

**Interfaces:**
- Produces: `ContractBoard`, `ContractAcceptResult`, `ContractClaimResult`, `ContractRerollResult`.
- Produces: `board(playerId, snapshot)`, `accept(playerId, offerId, snapshot)`, `claim(playerId, snapshot)`, and `reroll(playerId, snapshot)`.

- [ ] **Step 1: Write service RED tests**

Prove board loading, active-contract presentation, already-active rejection,
not-ready claim, successful claim refresh, duplicate-claim convergence,
one-reroll limit, new-cycle expiry, and storage-failure retry outcomes.

- [ ] **Step 2: Observe service RED**

Run: `./gradlew --no-daemon test --tests '*ContractServiceTest'`

- [ ] **Step 3: Implement service and transactional repository**

Add migration version 5. Acceptance locks the cycle and captures baseline.
Claim locks the contract, inserts reward event, updates progress, marks claimed,
and advances generation in one transaction.

- [ ] **Step 4: Add integration concurrency cases**

Compile tests for two simultaneous accepts, two simultaneous claims, reward
idempotency, reroll contention, cycle expiry, and migration replay.

- [ ] **Step 5: Run contract GREEN gates**

Run: `./gradlew --no-daemon test --tests '*ContractServiceTest' compileIntegrationTestKotlin`

### Task 7: Build passport submenus and command surfaces

**Files:**
- Create: `src/main/kotlin/ru/ruscrafting/ranks/gui/RankMenuItemFactory.kt`
- Create: `src/main/kotlin/ru/ruscrafting/ranks/gui/ContractMenu.kt`
- Create: `src/main/kotlin/ru/ruscrafting/ranks/gui/PerkMenu.kt`
- Create: `src/main/kotlin/ru/ruscrafting/ranks/gui/AnalyticsMenu.kt`
- Modify: `src/main/kotlin/ru/ruscrafting/ranks/gui/RankPassportMenu.kt`
- Modify: `src/main/kotlin/ru/ruscrafting/ranks/command/RankCommand.kt`
- Modify: `src/main/resources/plugin.yml`
- Test: `src/test/kotlin/ru/ruscrafting/ranks/gui/RankMenuContractTest.kt`
- Test: `src/test/kotlin/ru/ruscrafting/ranks/command/RankCommandContractTest.kt`

**Interfaces:**
- Produces: `/rank contracts`, `/rank perks`, `/rank admin analytics [7|14|30]` and equivalent inventory routes.
- Consumes: lifecycle-scoped async services, `RankLocale`, settings item specs, and cached snapshots.

- [ ] **Step 1: Write menu/command RED contract tests**

Assert exact slot ownership, back targets, admin visibility, permission name,
tab completion, console-safe analytics, stale callback tokens, and explicit
loading/error/empty/selected/locked/full states.

- [ ] **Step 2: Observe GUI contract RED**

Run: `./gradlew --no-daemon test --tests '*RankMenuContractTest' --tests '*RankCommandContractTest'`

- [ ] **Step 3: Extract the shared item factory**

Move filler and item construction from `RankPassportMenu`. The factory applies
configured custom model data and validates every root component as non-italic.

- [ ] **Step 4: Implement menus and routes**

Use dedicated holders, cancel top-inventory clicks/drags, keep one action per
slot, display rejection feedback locally, and restore only the latest menu
generation.

- [ ] **Step 5: Run GUI contract GREEN**

Run: `./gradlew --no-daemon test --tests '*RankMenuContractTest' --tests '*RankCommandContractTest' --tests '*RankPassportContractTest'`

### Task 8: Localize, compose, preview, and verify

**Files:**
- Modify: `src/main/resources/lang/ru.yml`
- Modify: `src/main/resources/lang/en.yml`
- Modify: `src/main/kotlin/ru/ruscrafting/ranks/text/RankLocale.kt`
- Modify: `src/main/kotlin/ru/ruscrafting/ranks/paper/ArcRanksPlugin.kt`
- Modify: `visual-preview.yml`
- Modify: `README.md`
- Modify: `ARCHITECTURE.md`
- Test: `src/test/kotlin/ru/ruscrafting/ranks/text/RankLocaleTest.kt`
- Test: `src/test/kotlin/ru/ruscrafting/ranks/PluginDescriptorTest.kt`

**Interfaces:**
- Consumes every preceding service and menu.
- Produces the final Paper composition root, complete RU/EN surface catalog,
  low-cardinality ArcMetrics snapshot, health contribution, visual report, and
  production shadow JAR.

- [ ] **Step 1: Write locale/descriptor RED tests**

Require every new scalar/list path, exact RU/EN parity, analytics permission,
and non-italic names/lore for every new menu state.

- [ ] **Step 2: Observe locale RED**

Run: `./gradlew --no-daemon test --tests '*RankLocaleTest' --tests '*PluginDescriptorTest'`

- [ ] **Step 3: Add concise Russian and English surfaces**

Keep warm semantic colors, one action hint per tooltip, no gradients, no
streak/FOMO copy, and explicit explanations that perks enhance existing play.

- [ ] **Step 4: Compose lifecycle and cached health**

Initialize migrations before listeners, warm perks on join, schedule telemetry
flush through `LifecycleTaskScope`, add bounded metrics/health, and flush once
on shutdown without extending the existing shutdown timeout.

- [ ] **Step 5: Run complete unit/package verification**

Run: `./gradlew --no-daemon test compileIntegrationTestKotlin shadowJar --rerun-tasks`

Run: `python3 ../arc-core/scripts/verify_consumer_architecture.py .`

- [ ] **Step 6: Render and inspect every visual surface**

Run: `./scripts/render-visual-preview --ops-root ../.deploy-ruscrafting-ops`

Require coverage `100%`, no unresolved placeholders, and zero automatic wraps.
Inspect every inventory overview and tooltip sheet; for each discovered visual
or state bug, first add a failing contract test when the defect is behavioral,
then fix and rerender the entire dump.

- [ ] **Step 7: Inspect the release artifact**

Verify ZIP integrity, plugin descriptor, shaded Kotlin/arc-core classes,
absence of Paper/MockBukkit classes, size, SHA-256, and exact source diff.

- [ ] **Step 8: Commit the verified trunk**

Stage only ArcRanks files and commit on local `main`. Report separately that
CI integration execution, remote publication, lab, production deployment, and
owner visual approval remain outside local implementation evidence.
