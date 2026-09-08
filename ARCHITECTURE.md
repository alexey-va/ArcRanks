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

`quest/DailyQuest` defines three optional daily goals in existing path-point
units: farming 100/+10, industry 100/+10, exploration 2000/+200. These are
small additive bonuses, not mandatory rank gates, currencies or BattlePass
season rewards. The existing evaluator sees the permanent bonus immediately
on its next authoritative load. Active-time requirements remain unchanged.

`MySqlProgressRepository.applyMutations` feeds locally collected gameplay
into `MySqlDailyQuestRepository` in the same SQL transaction. Daily rows are
locked per player/quest; crossing the target credits the permanent bonus once.
External/admin grants and contract rewards do not feed daily counters; daily
bonuses do not recurse. Existing collection rules, weights and perk modifiers
still apply, so cards deliberately say path points rather than raw item counts.
A bonus can advance an already accepted weekly contract because that contract
uses the same permanent path metric; its three-claim weekly cap is unchanged.

Migration 11 retains only three daily rows per participating player. A new UTC
day replaces only their daily values, never permanent ranks or specialization
progress. The day is assigned when the existing coalesced batch is persisted
(normally within 15 seconds); a boundary batch belongs to that persistence day.
A backend with an older day cannot overwrite a newer stored day. No historical
statistics are imported. This feature inherits the buffer's crash/ambiguous
commit limitations for raw progress; the daily completion marker and bonus
always commit or roll back together.

`DailyQuestMenu` is a 27-slot read-only chest board. `/rank quests` (`daily`
alias), the passport entry and the native dialog entry open the same board.
Opening/refreshing flushes local buffered actions; there is no accept/claim
button and no polling task. All callbacks check inventory ownership and config
generation. Existing filler theme, locale merge and menu-session cleanup apply.
Defaults add the new layout and locale keys without replacing operator values.

Focused verification:
`./gradlew test --tests 'ru.ruscrafting.ranks.quest.DailyQuestTest' --tests 'ru.ruscrafting.ranks.gui.ArcRanksMenuLayoutsTest' --tests 'ru.ruscrafting.ranks.config.ArcRanksConfigurationTest' shadowJar`.
The existing MySQL integration test additionally covers concurrent completion,
admin exclusion, restart-style repository reuse, next-day reset and rollback;
run it in CI or when integration validation is explicitly requested.
