# ArcRanks architecture

## Authority boundaries

- LuckPerms owns the permanent main-rank group.
- MySQL owns monotonic progress, selected focus, idempotent external events,
  and the promotion saga.
- ArcRanks owns evaluation, commands, GUI, placeholders, and event sampling.
- Existing plugins keep ownership of their gameplay mechanics and permission
  nodes. ArcRanks describes benefits but never derives permissions from prose.

## Promotion safety

A healthy player is either on LuckPerms' implicit `default` group with no
direct progression parent, or has exactly one direct parent from the configured
progression set. `Missing`, `Conflict`, and provider failures are typed states
and fail closed. Promotion first flushes local progress, reloads authoritative
values, persists `PREPARED`, applies the exact expected group replacement,
verifies the resulting group, and persists `COMPLETED`. Retrying resumes the
same generation; unrelated groups are never cleared.

The shipped `SHADOW` mode counts and renders progress without changing
LuckPerms. Moving to `ACTIVE` is an ops cutover after player-state diagnostics
and permission review.

## Progress model

Every target rank requires active minutes plus `N` completed goals selected
from farming, industry, trade, exploration, building, and community. All paths
advance simultaneously. Missing optional providers mark only their path as
unavailable. The evaluator blocks only if fewer than `N` paths remain.

Local high-frequency counters use a bounded coalescing buffer. A successful
promotion always flushes that player's buffer before evaluation. External
plugins use `RankProgressApi`, whose durable `source + eventId` identity makes
delivery idempotent across retries and servers.

## Threading and lifecycle

JDBC runs exclusively through `SqlRuntime`. Bukkit inventory, title, sound,
and command callbacks return through the arc-core lifecycle task scope. The
runtime owns SQL, metrics, repeating samplers, and health reporting. Startup
fails closed if MySQL or LuckPerms is unavailable; Vault and PlaceholderAPI
are optional.

## Localization and GUI

`lang/ru.yml` and `lang/en.yml` must keep exact leaf-key parity. Catalog keys,
command surfaces, and GUI names/lore are validated at startup and in tests.
GUI item roots explicitly disable italics. The source theme uses warm
terracotta `#d9864f`, amber `#f4bd6a`, cream `#fff0d8`, and brown-gray
`#8d7768`; runtime may replace the portable filler with `arc:background`.
