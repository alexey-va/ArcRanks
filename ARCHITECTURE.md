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
one transaction. The money, RedisEconomy token, and ARC item components are
then delivered through the shared arc-core one-time-use ledger. Each component
has a deterministic cross-server identity; rejected provider calls remain
pending, while unknown outcomes enter explicit recovery instead of risking an
automatic duplicate. Pending rewards resume when the player joins. Missing a
week has no penalty.

`arcranks.admin.contract` exposes a deliberately separate test operation. It
sets an audited `admin_completed_at/admin_completed_by` marker on the locked
active contract but never changes path progress, inserts a reward event, or
marks the contract claimed. The ordinary claim transaction remains the only
way to receive the reward, so an administrator can test the real player flow
without manufacturing target progress or creating a second reward path.

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
GUI item roots explicitly disable italics. The source theme uses clean warm
amber `#f4bd6a`, cream body `#fff0d8`, neutral structure `#8c8c8c`, and
semantic green/warning/error colors; runtime may replace the portable filler
with `arc:background`.

## Daily quests

`daily-quests.yml` owns a 24-template pool, concrete objective counters, path
bonuses, coin rewards and a rank-to-count map (6/8/10/12/14/16/18/20/21 by default).
Selection is deterministic by player and UTC date and interleaves paths. At most
one quest becomes rare (25% daily chance, doubled target, 150 coins and 1 token);
ordinary quests pay 50 coins. All quantities and chances are configurable.
A promotion or reload changes the next assignment; today's snapshot cannot be
rerolled. Quests assist existing permanent paths; promotion requirements and
active-time gates stay authoritative.

Ordinary gameplay counts harvest, fish, breed, craft operations, smelted items,
enchant, smith, villager trade, placed blocks, decoration, travel, advancements,
active minutes and community minutes. Existing collector eligibility and repeat
gates apply. Quest counters use concrete action quantities, independent of perk
multipliers. Builder committed operations count tool uses and accepted positions.
EliteMobs completed runs count their original participants still present at the
finish; the Mines goal matches `em_id_the_mines`, not a client-entered name.
ArcFarms `WorkShiftCompletedEvent` counts real farm/lumber/mine contributors.
ARC `ResourceContractCommittedEvent` counts committed submissions and quantity.
These optional public events are consumed reflectively without new hard plugin
dependencies; provider updates must accompany activation of their goals across
the network. An unavailable integration logs a warning; it does not fabricate
progress. Keep unavailable activities out of the configured network pool.

Migration 12 freezes assignments under a per-player board lock and retains a
separate reward outbox. Local progress, completion, permanent path bonuses and
currency obligations commit together. Rollover replaces only current goals;
pending rewards survive. External `RankQuestApi` events have source-scoped,
stable IDs and persistent deduplication; a duplicate does not count again even
on another day. Provider emission is not a transactional network outbox: a
crash before emission can lose an observation, and bounded retries cannot
promise recovery after a prolonged outage. Local buffered raw actions retain
the existing crash/ambiguous-commit limitations. Dedup and reward history are
retained; do not prune IDs without a coordinated source replay horizon.

`RankRewardDeliveryService` shares the existing one-time ledger with legacy
contracts, using distinct `daily` and `contract` identities. Confirmed failures
remain pending; uncertain provider outcomes go to recovery, never blind replay.
Delivery runs after progress and on join/startup; confirmed grants notify the
player. New weekly rank offers are retired. Previously accepted contracts and
pending grants remain available through `/rank legacy-contracts`; ARC resource
contracts are independent and remain active.

`DailyQuestMenu` is read-only and uses 3..5 chest rows for 1..21 cards, seven per
row. `/rank quests`, `/rank daily` and `/rank contracts` open the same board,
as does the existing contracts entry in the passport. The root menu does not
grow. Header status uses the configured anchor; back/refresh anchors shift with
the footer. Card slots follow `DailyQuestLayout`; the old three-card region and
extra passport daily slot are compatibility configuration, no longer rendered.
All async rendering checks holder identity and config generation. Opening and
refreshing flush buffered actions; no accept or reward-claim click is needed.

Focused verification includes quest catalog/geometry, payout identity, progress
buffer, contract compatibility and configuration tests. MySQL integration tests
cover completion, rollback, assignment freeze, rollover and event deduplication;
compile them locally and run them in CI or explicit integration validation.
