# Rank Rewards Production Design

## Goal

Turn the nine ArcRanks progression stages into useful, visible upgrades while
keeping the server's interesting gameplay available from the first login.

## Authority

- ArcRanks owns rank progress, promotions, weekly-kit claim state, presentation,
  and product telemetry.
- LuckPerms owns effective gameplay privileges. Each progression group inherits
  only `default`; staff and donor groups stay independent.
- CMI owns the native kit definitions. ARC's typed CMI API is the only writer;
  `Kits.yml` is never edited by hand.
- ARC owns NPC resource contracts and resolves rank modifiers from LuckPerms
  numerical permissions.
- zMenu only links to `/rank`; it does not duplicate rank state or lore.

## Player progression

| Rank | Group | Jobs | Homes | Auctions | Player warps | Contract cap | Contract payout |
|---|---|---:|---:|---:|---:|---:|---:|
| Settler | `default` | 1 | 5 | 5 | 0 | 100% | 100% |
| Peasant | `rank_peasant` | 2 | 6 | 7 | 0 | 110% | 102% |
| Citizen | `rank_citizen` | 3 | 7 | 10 | 0 | 120% | 104% |
| Artisan | `rank_artisan` | 4 | 8 | 12 | 0 | 130% | 106% |
| Knight | `rank_knight` | 4 | 10 | 15 | 1 | 140% | 109% |
| Baron | `rank_baron` | 4 | 12 | 20 | 2 | 150% | 112% |
| Count | `rank_count` | 4 | 15 | 25 | 3 | 165% | 116% |
| Prince | `rank_prince` | 4 | 20 | 30 | 4 | 180% | 120% |
| Caesar | `rank_caesar` | 4 | 25 | 40 | 5 | 200% | 125% |

Lands limit packs scale from 1 land / 32 free chunks / 64 maximum chunks to
6 lands / 512 free chunks / 1024 maximum chunks. Trusted players, members and
areas rise with the same progression. Board publishing opens at Baron and
join/leave-message customization opens at Count.

## Weekly rank kits

- A player may claim exactly one current-rank kit per Moscow calendar week.
- Promoting during a claimed week does not create a second claim.
- A claim first acquires a shared-MySQL delivery reservation. A provider refusal
  releases it; an ambiguous crash leaves it locked for manual reconciliation and
  never blindly duplicates rewards.
- The menu checks a configured number of empty inventory slots before reserving.
- CMI kit cooldown is zero and players receive no direct `cmi.kit.*` permission;
  ArcRanks is the only player-facing claim path.
- Each kit contains practical supplies, one or more ARC loot pouches and a signed
  ArcEcoJobs all-jobs booster. The current rank replaces lower-rank kits rather
  than accumulating nine weekly claims.

## Presentation

- The passport stays five rows with the shared warm `arc:background` texture,
  symmetric navigation, an empty visual row and no close button.
- A weekly-kit card and a current-benefits card flank the promotion card.
- The kit preview is a separate five-row screen with summary, contents, claim,
  and one back arrow.
- Promotions use a bounded warm particle ring. Artisan through Baron get one
  harmless tagged firework; Count through Caesar get two. Tagged visual
  fireworks cannot damage entities.
- Count, Prince and Caesar promotions publish one network-wide announcement via
  ARC's existing `/x` transport, with local fallback if the transport is absent.

## Rollout

Deploy code and runtime configuration to production, but keep the new entry
point and ArcRanks command permissions limited to the owner's canary identity.
Do not migrate or delete legacy player rank groups globally in this rollout.

