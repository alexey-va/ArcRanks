# ArcRanks Passport, Contracts, Perks, and Analytics Design

**Date:** 2026-08-29
**Status:** approved for implementation by the owner
**Component:** standalone `ArcRanks` Paper plugin

## Outcome

Turn the rank passport into the lightweight home for permanent progression.
Add personal contracts and freely switchable specialization perks without
placing public server mechanics behind ranks. Collect enough product telemetry
to balance the system, while keeping every gameplay hot path free from SQL,
LuckPerms, Vault, logging, and unbounded metric labels.

## Product Rules

- Permanent ranks, specialization progress, contract completions, and selected
  perks never reset.
- Contract offers use weekly cycles, but missing a cycle has no penalty, lost
  streak, or catch-up debt.
- A player may complete up to three contracts per weekly cycle. Each generation
  offers three deterministic choices and one free reroll.
- One contract may be active at a time. Completion rewards a bounded permanent
  bonus to the same path and one passport stamp.
- Every player has two active perk slots. A perk may require path mastery, but
  no perk grants access to a public mechanic that was otherwise unavailable.
- Perks improve path progress, contract target size, or contract reward size.
  They do not increase combat damage, generate currency, bypass protection, or
  grant moderation/infrastructure permissions.
- The passport remains useful with contracts or analytics temporarily
  unavailable; each submenu has loading, empty, unavailable, and retry states.

## Product Telemetry

### Events

The bounded event catalog records:

- player seen;
- passport, contract board, perk board, and analytics board opened;
- recommendation family shown;
- promotion attempt and typed result;
- contract offered, accepted, rerolled, claimed, expired, and claim rejected;
- perk equipped, removed, rejected, and bonus progress awarded.

Dimensions are validated catalog identifiers only: rank, path, perk, contract
result, promotion result, or `none`. Player UUIDs and names never become metric
labels or rollup dimensions.

### Hot-path invariant

`ProductTelemetry.record` and `recordPlayerSignal` only merge into bounded
memory maps. They do not call SQL, Vault, LuckPerms, Bukkit scheduling, logging,
Prometheus exposition, or another plugin. The maximum rollup-key count and
maximum pending-player count are configured and expose dropped-entry counters.

Once per minute, the lifecycle scheduler drains a snapshot and submits one
asynchronous SQL transaction. A failed transaction retains that exact snapshot
and batch UUID for retry; events received during the failed write stay in a
separate next batch. Shutdown drains queued batches within one bounded wait.

### Durable tables

`arc_ranks_product_metric_hour` stores UTC-hour rollups keyed by hour, server,
event, and bounded dimension. Atomic `ON DUPLICATE KEY UPDATE` addition allows
every backend to flush independently.

`arc_ranks_product_player_day` stores one coalesced row per UTC day and player.
It contains only product-funnel flags and the latest observed rank: seen,
passport opened, contract accepted, contract completed, and perk selected.
It is not exported as a labelled Prometheus series.

### Operator summary

`/rank admin analytics [7|14|30]` and the permission-gated analytics menu load
data asynchronously only on operator request. Results are cached for 60 seconds
and include:

- unique seen players and passport reach;
- contract accept and completion reach;
- perk-selection reach;
- promotion attempts and successful promotions;
- recommendation/blocker distribution;
- telemetry queue size, dropped entries, flush failures, and last successful
  flush age.

Prometheus receives only low-cardinality gauges for queue health, drops,
failures, and last-flush age. Durable product totals come from MySQL rollups,
not process-lifetime counters.

## Personal Contracts

### Offer generation

The week starts Monday at 00:00 UTC. Offer identity is deterministic from
player UUID, cycle start, contract generation, reroll nonce, and path. The
three choices prioritize:

1. selected focus;
2. the evaluator's nearest incomplete path;
3. a deterministic distinct available path.

Unavailable provider paths are excluded. Targets use configured per-path base
amounts scaled by current permanent-rank order and reduced by an equipped
contract-target perk. Rewards are a configured percentage of the accepted
target and may be increased by a contract-reward perk. All arithmetic is
bounded and uses integer basis points.

### State and persistence

`arc_ranks_contract_cycle` owns cycle start, generation `0..3`, and reroll
nonce. `arc_ranks_contract` stores the accepted offer, baseline metric value,
target delta, reward delta, state, and timestamps.

The state machine is:

```text
OFFERED -> ACTIVE -> CLAIMED
                  \-> EXPIRED
```

`OFFERED` is deterministic and not persisted. Acceptance runs in one
transaction, locks the player's cycle row, rejects another active contract,
and captures the authoritative metric baseline. Claim locks the active row,
checks the authoritative metric against baseline plus target, inserts the
idempotent reward event, atomically adds the reward progress, marks the
contract claimed, and advances the generation in the same transaction.
Retry cannot duplicate a reward or stamp.

## Specialization Perks

`perks.yml` defines two perks per path:

- momentum perk: mastery I, `PROGRESS_BONUS` for counter metrics;
- contract perk: mastery II, `CONTRACT_TARGET_REDUCTION` for every path except
  trade, whose second perk is `CONTRACT_REWARD_BONUS`.

The catalog validates safe unique IDs, exact path ownership, mastery floor,
effect compatibility, and basis-point bounds. Two global active slots are
available to every player. Clicking an equipped perk removes it; clicking an
unlocked perk fills the first free slot; a third selection is rejected without
changing stored state.

`arc_ranks_perk_selection` persists slot and perk ID. Selection changes are
transactional. Join and selection refresh the existing non-blocking player
snapshot cache, so progress listeners never query MySQL.

Counter progress bonuses use a bounded fractional accumulator. For a 10%
bonus, ten one-unit events yield one extra unit deterministically instead of a
random chance. At most a sub-unit remainder is lost on disconnect or crash.
High-water metrics such as wealth never receive synthetic progress bonuses.

## Passport UX

The existing 54-slot passport remains the root. It keeps the nine-rank ladder,
six path cards, recommendation, benefits, refresh, promotion, and close. Three
clear branches are added without increasing tooltip density:

- contracts: `WRITABLE_BOOK`;
- perks: `ENCHANTED_BOOK`;
- admin analytics: `SPYGLASS`, visible only with
  `arcranks.admin.analytics`.

The contract menu shows cycle status, active contract or three offers, one
reroll control, completed stamps, and a stable back button. The perk menu shows
two active slots and twelve path-grouped perk cards with selected, available,
locked, and full-slot feedback states. The analytics menu shows product funnel,
contracts, perks, promotion, blockers, and telemetry health as separate cards.

All menus use the existing warm terracotta, honey, cream, brown-gray, success,
warning, and error roles. Every item name and lore root is explicitly
non-italic. Portable source defaults use vanilla materials; runtime filler may
use verified `arc:background` custom model data.

## Localization and Preview

Russian and English catalogs keep exact leaf-key parity. Code owns identifiers,
placeholder schemas, slot wiring, and state transitions; locale files own every
title, item name, lore row, chat line, rejection reason, and analytics label.

`visual-preview.yml` must render:

- the expanded passport states;
- contracts: offers, active, ready-to-claim, cycle-complete, loading, and error;
- perks: available, selected, locked, slots-full feedback, loading, and error;
- analytics: populated, empty, loading, and error;
- new command and claim feedback.

Acceptance requires 100% selected-key coverage, zero unresolved placeholders,
zero automatic chat wraps, and manual inspection of every generated inventory
overview and tooltip sheet.

## Failure and Performance Boundaries

- Telemetry failure never blocks rank progress, contracts, perks, or menus.
- Contract/perk storage failure returns a localized retry state and performs no
  partial mutation.
- Analytics queries never run periodically and never run on the Paper thread.
- Contract and perk menus discard stale async callbacks after close/reopen.
- Repeated clicks cannot accept two contracts, claim twice, consume two perk
  slots, or restore an obsolete feedback item.
- Health probes read cached counters only and perform no SQL or Bukkit work.
- No production deployment, server restart, permissions rebalance, or remote
  repository creation is part of this implementation.

## Verification

- Pure tests cover telemetry coalescing/failure restoration, offer
  determinism, cycle boundaries, perk unlock/effects, fractional bonuses, and
  typed service results.
- MySQL integration tests cover migrations, concurrent acceptance, atomic
  claim/reward, duplicate claim, perk slot limits, rollup addition, and funnel
  upserts; they compile locally and execute in CI only.
- GUI/localization tests cover menu routes, slot ownership, state variants,
  non-italic roots, key parity, and required placeholders.
- Final local gates are unit tests, integration-test compilation, shadow JAR,
  arc-core consumer verification, visual dump, artifact inspection, checksum,
  and exact diff review.
