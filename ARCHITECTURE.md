# ArcRanks architecture

## Authority boundaries

- LuckPerms owns the permanent main-rank group.
- MySQL owns monotonic progress, selected focus, perk selection, contract
  cycles and claims, idempotent external events, product rollups, and the
  promotion saga.
- ArcRanks owns evaluation, commands, GUI, placeholders, and event sampling.
- Existing plugins keep ownership of their gameplay mechanics and permission
  nodes. ArcRanks describes benefits but never derives permissions from prose.

## Promotion safety

A healthy player is either on LuckPerms' implicit `default` group with no
direct progression parent, or resolves to the highest direct parent from the
configured progression set. Legacy CMI/ARC sync may leave cumulative lower-rank
parents on a player; those are accepted as one linear rank history rather than
misclassified as a conflict. Promotion first flushes local progress, reloads
authoritative values, persists `PREPARED`, replaces only configured progression
parents with the target group, verifies the resulting group, and persists
`COMPLETED`. Retrying resumes the same generation; donor, staff, temporary, and
other non-catalog groups are never cleared.

The plugin default remains `SHADOW`, which counts and renders progress without
changing LuckPerms. The production ops profile uses `ACTIVE` only for the
permission-gated preview audience after player-state diagnostics.

## Progress model

Every target rank requires active minutes plus `N` completed goals selected
from farming, industry, trade, exploration, building, and community. All paths
advance simultaneously. Missing optional providers mark only their path as
unavailable. The evaluator blocks only if fewer than `N` paths remain.

Local high-frequency counters use a bounded coalescing buffer. A successful
promotion always flushes that player's buffer before evaluation. External
plugins use `RankProgressApi`, whose durable `source + eventId` identity makes
delivery idempotent across retries and servers.

## Contracts and perks

Contract offers are deterministic from player, Monday-UTC cycle, generation,
reroll nonce, and path. Accept captures the authoritative metric baseline.
Claim locks the active contract, checks flushed progress, inserts an idempotent
reward event, adds the reward, marks the stamp, and advances the generation in
one transaction. Missing a week has no penalty.

Every player has two perk slots. Mastery unlocks enhancements to existing play,
never base mechanics, combat, money, or protection bypasses. Counter bonuses
use a per-player fractional accumulator and cached perk selection; wealth keeps
its high-water sampling semantics.

## Product telemetry

Gameplay records merge only into bounded in-memory maps. One idempotent batch
per minute updates hourly event rollups and daily player funnel flags. A failed
write keeps the exact batch UUID for an isolated retry; events received while
that write is in flight remain in the next batch. UUIDs
exist only in the daily SQL cohort table and never in Prometheus labels.
Operator summaries run on demand through `SqlRuntime` and are cached for 60
seconds. Shutdown drains queued batches within the existing five-second bound.
Telemetry failure does not block ranks, contracts, perks, or menus.

## Threading and lifecycle

JDBC runs exclusively through `SqlRuntime`. Bukkit inventory, title, sound,
and command callbacks return through the arc-core lifecycle task scope. The
runtime owns SQL, repeating telemetry flushes, and health reporting. Startup
fails closed if MySQL or LuckPerms is unavailable; Vault and PlaceholderAPI
are optional.

## Localization and GUI

`lang/ru.yml` and `lang/en.yml` must keep exact leaf-key parity. Catalog keys,
command surfaces, and GUI names/lore are validated at startup and in tests.
GUI item roots explicitly disable italics. The source theme uses the shared
RusCrafting blue `#92bed8`, pale body `#e6fff3`, neutral structure `#8c8c8c`,
and semantic green/warning/error colors; runtime may replace the portable
filler with `arc:background`.
