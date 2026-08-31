# Аудит ArcRanks, исправления и MockBukkit-регрессии

Дата: 2026-08-31

Объект: `ArcRanks` 0.2.1, Paper/Purpur 1.21.11, Java 25, Kotlin 2.3.0, arc-core 2.1.3, LuckPerms API 5.5, MockBukkit 4.110.0 (transitive artifact)

Режим: статический аудит, исправления в source worktree и локальные unit/MockBukkit-проверки; production, MySQL и runtime-профили не изменялись

## Итог

Исходный аудит обнаружил четыре дефекта высокого приоритета:

1. повышение из GUI не проверяет `arcranks.rankup`;
2. временный или контекстный LuckPerms-ранг может превратиться в постоянный глобальный ранг;
3. `ProgressBuffer.flush*()` не является барьером сохранения, хотя повышение и shutdown используют его как барьер;
4. успешный `/rank admin reload` отменяет и не восстанавливает периодические задачи прогресса, аналитики и health-reporting.

Текущий source worktree исправляет AR-001—AR-006, AR-008, AR-009 и AR-011—AR-013.
Исправления включают permission gate, фильтрацию LuckPerms nodes,
sequence-barrier буфера, атомарные config snapshots и стабильный periodic
heartbeat, прямой вход GUI в recovery-aware promotion service, сериализацию выбора
focus, границу каталога, cache-generation tokens, доставку результатов действий
после закрытия меню и client-locale-aware placeholders. Финальный локальный gate
`test compileIntegrationTestKotlin shadowJar` завершился успешно; ниже по-прежнему
различаются «реализовано в коде», точечная regression-проверка и границы, которые
могут быть доказаны только настоящим MySQL/LuckPerms integration окружением.

Из исходной матрицы открытыми остаются AR-007 и AR-015. AR-010 теперь наблюдаем:
отказы capacity считаются и переводят health в DOWN, но сама отклонённая мутация
по-прежнему теряется, а operator/producer policy не завершена. Для AR-014 исправлен
bundled fallback Back asset, но runtime-профили и production не менялись, поэтому
это source-ready изменение с незавершённым rollout, а не подтверждённый production fix.

Дополнительный review именно hot-reload/config поверхности добавил AR-016—AR-032.
Текущий source закрывает AR-016—AR-024 и AR-030—AR-032: cadence не сбрасывается, perk
effects сохраняют last-known context и учитывают новые mastery rules, loader
отвергает torn generations, logging-only reload распознаётся, `gui.items`
валидируется точно, появились feature gates/material filters, delayed fireworks
отменяются при смене celebration settings, а in-flight perk action использует
locale своего поколения. AR-025—AR-027 и AR-029 остаются открытыми; AR-028 закрыт
частично.

Финальный независимый review не обнаружил новых P0/P1. Два найденных P2 — race
perk mutation с reload warmup и пропущенная валидация weekly-kit icon — уже
закрыты в текущем source как AR-031/AR-032 и получили точечные unit-регрессии.
Финальный Gradle gate, consumer architecture verifier и полный visual preview
также завершились успешно; точные результаты приведены в разделе проверки.

На момент исходного аудита в проекте было 28 unit-наборов (98 случаев) и 6 MySQL
integration-наборов. Из них только два случая открывали MockBukkit, но проверяли
константы и имена `Material`, не создавая игрока, инвентарь или событие. Текущий
worktree расширяет `RankPassportMenuMockBukkitTest` до пяти реальных платформенных
сценариев, добавляет по одному menu-session, client-locale placeholder и feature
gate MockBukkit-сценарию, а также шесть unit-сценариев координатора reload.

## Граница security-проверки

Недоверенный субъект для AR-001 — обычный игрок с `arcranks.use`, но без
`arcranks.rankup`; привилегированный эффект — постоянная смена LuckPerms-ранга.
Недоверенное состояние для AR-002 — прямой rank-node с ограниченным сроком или
контекстом; привилегированный эффект — создание постоянного глобального parent.

Оба пути были подтверждены в исходной ревизии на repository-слое и точном
локальном LuckPerms API 5.5 JAR; текущий source закрывает эти code paths описанными
ниже guards.
Live production и player data не читались, поэтому фактическая эксплуатация в
production не заявляется. Confidence для обоих findings — **confirmed code path**.
Blast radius AR-001 — игроки с `arcranks.use`, но без `arcranks.rankup`; AR-002 —
игроки с expiring/context-bound progression node, которые также достигают eligibility
и могут инициировать promotion route. Ротация секретов не требуется.

Value-safe scanner по `ArcRanks` дал один review lead:
`presentation/PromotionCelebration.kt`, console command sink. Он не повышен до finding:
player name ограничен форматом Minecraft, rank text приходит из доверенного
операторского locale, а произвольного player-controlled command fragment в цепочке
нет. Остаточный риск — зависимость от грамматики CMI; проверка typed API у CMI
остаётся вне текущего scope.

## Findings и текущий статус

### AR-001 — P1 — GUI обходит право `arcranks.rankup`

**Статус: исправлено в текущем source и покрыто MockBukkit.**

`RankPassportMenu.promote()` теперь до проверки режима и вызова service требует
`player.hasPermission("arcranks.rankup")`. Четвёртый сценарий
`RankPassportMenuMockBukkitTest` создаёт настоящий `PlayerMock`, выдаёт только
`arcranks.use`, запрещает `arcranks.rankup`, кликает promotion slot через
`PluginManager` и проверяет ноль вызовов `PromotionService.promote` и
локализованный `commands.no-permission`.

Закрытый риск: GUI и `/rankup` снова соблюдают одну административную модель прав.

### AR-002 — P1 — временный/контекстный LuckPerms-ранг становится постоянным

**Статус: исправлено в текущем source; classification guard покрыт unit-тестом,
точная mutation semantics остаётся CI/integration gate.**

`LuckPermsRankStateGateway` теперь использует только положительные постоянные
глобальные `InheritanceNode`: `value == true`, без expiry и с пустым context set.
Тот же predicate применяется и при классификации текущего ранга, и при удалении
progression parents. Временные, контекстные и negated nodes не становятся rank
authority и не попадают под очистку.

`RankStateGatewayTest` строит permanent, expiring, contextual и negated nodes на
точном LuckPerms API 5.5 и проверяет predicate. Полный `modifyUser` сценарий с
byte-for-byte сохранением group/value/expiry/contexts всё ещё должен выполняться
в LuckPerms consumer/integration окружении, а не считаться доказанным одним mock predicate.

### AR-003 — P1 — `ProgressBuffer.flush` и `flushAll` не гарантируют сохранение

**Статус: исправлено в текущем source и покрыто unit-регрессиями.**

`ProgressBuffer` присваивает принятым мутациям sequence, хранит accepted/persisted
watermarks на игрока и продолжает `flushThrough` после уже идущей пачки, пока не
достигнут watermark конкретного вызова. `flushAll()` фиксирует bounded snapshot
targets и ждёт также in-flight batch при пустом `pending`; более поздний поток
событий не может бесконечно расширять барьер. Неуспешная пачка возвращается в
`pending`, а barrier завершается ошибкой.

`ProgressBufferTest` отдельно проверяет pending-behind-in-flight, bounded barrier,
in-flight-only `flushAll`, восстановление точных мутаций после ошибки и обычную
агрегацию/capacity semantics.

### AR-004 — P1 — reload навсегда останавливает периодические задачи

**Статус: исправлено в текущем source и покрыто cadence unit-тестами; полный
детерминированный scheduler wiring сценарий ещё полезен.**

`ArcRanksRecurringTasks` ставит один gameplay heartbeat раз в секунду. Три
долгоживущих `DynamicTickCadence` читают periods из active snapshot и запускают
sampler, progress flush и analytics flush. Health scheduling остаётся у
стандартного `runtime.reportHealthEvery` из arc-core. Обычный live reload не
трогает ни gameplay heartbeat, ни health task. Только изменение
`runtime.health-report-ticks` транзакционно переустанавливает обе runtime-owned
задачи; сами cadence objects не пересоздаются и сохраняют elapsed carry.

`DynamicTickCadenceTest` проверяет точный due boundary, немедленный выпуск
накопленного carry при сокращении и отсутствие раннего запуска при увеличении.
MockBukkit-проверка реального wiring трёх gameplay действий и условной
переустановки heartbeat/health пока остаётся в матрице ниже.

### AR-005 — P2 — неудачный reload частично меняет активные locale-конфиги

**Статус: исправлено в текущем source и покрыто unit-регрессиями.**

`ArcRanksConfigLoader` строит полностью изолированный candidate из новых `Config`
и `RankLocale.fresh`, валидирует все семь публичных YAML и только затем передаёт
snapshot координатору. Hashes до и после parse обязаны совпасть. Active snapshot
публикуется одним CAS; stable heartbeat сам начинает читать новое поколение.
Invalid, no-op, restart-only и concurrent candidates не меняют active store.

`ArcRanksConfigurationTest` проверяет, что повреждённый locale candidate не
меняет старый renderer и версию `ConfigManager`. Шесть сценариев
`ArcRanksReloadCoordinatorTest` покрывают no-op, invalid, restart-only, commit
ordering, hook rollback и concurrent `Busy`. Production hooks фактически ничего
не меняют для reload с прежним health period; при его изменении candidate tasks
ставятся до CAS, а общий rollback seam возвращает старую пару heartbeat/health
при ошибке установки или конкурентной смене active generation.

### AR-006 — P2 — GUI не запускает восстановление незавершённой promotion saga

**Статус: исправлено в текущем source; отдельный GUI recovery-сценарий ещё не
добавлен.**

В ACTIVE mode GUI после permission gate всегда вызывает
`PromotionService.promote()` и больше не блокирует service локальной проверкой
eligibility старого snapshot. Поэтому recovery-aware service получает управление
и для `APPLIED` saga. Обычный `NotEligible` возвращается как локализованный result
и временно отображается в promotion slot.

Остаётся добавить платформенный сценарий: seed `APPLIED`, уже переключённый LP
target и не-`READY` snapshot следующего ранга; service должен завершить recovery
без второй LP mutation и второй celebration.

### AR-007 — P2 — weekly kit может навсегда застрять в `DELIVERING`

**Статус: открыт.**

`WeeklyKitService` резервирует row до внешней доставки (`WeeklyKitDomain.kt:104-140`). Если provider принял выдачу, а `confirm` завершился ошибкой/неопределённо, сервис возвращает `DeliveryPending`. Повторный `begin` видит любой не-`CLAIMED` row как `DeliveryPending` (`MySqlWeeklyKitRepository.kt:56-66`).

В схеме есть `updated_at` и индекс `(state, updated_at)`, но нет lease/recovery/reconciliation. Автоматического или операторского пути из stale `DELIVERING` нет.

**Риск:** игрок может навсегда потерять kit текущего недельного цикла; слепой retry опасен двойной выдачей.

**Регрессия:** отдельные MySQL integration-сценарии для crash до provider, provider-success/confirm-timeout и provider-reject/release-timeout; recovery должен быть идемпотентным и различать подтверждённую, отклонённую и неопределённую доставку.

### AR-008 — P2 — быстрые клики по специализациям создают гонку

**Статус: исправлено в текущем source и покрыто MockBukkit.**

Выбрана однозначная first-intent политика: первый path click ставит
`actionPending`; все остальные клики ArcRanks menu игнорируются до completion.
Completion отправляет result независимо от текущего inventory, но меняет holder/UI
только если игрок всё ещё находится в том же меню.

Пятый сценарий `RankPassportMenuMockBukkitTest` делает два быстрых клика при
pending future и подтверждает ровно один `selectFocus` для первого пути, ноль для
второго и согласованное сообщение после завершения.

### AR-009 — P2 — каталог допускает больше рангов, чем GUI и celebration

**Статус: исправлено в текущем source.**

`RankCatalog` теперь fail-fast отклоняет больше девяти определений с понятным
сообщением до открытия GUI или celebration. Это согласует допустимый catalog с
фиксированной девятислотовой rank topology и девятью celebration profiles.
Желательна ещё одна явная loader-регрессия с десятью последовательными рангами;
сам guard уже находится на общей construction boundary.

### AR-010 — P2 — переполнение буфера отбрасывает прогресс

**Статус: частично исправлено — отказ наблюдаем, loss policy остаётся открытой.**

`ProgressBuffer.record*()` по-прежнему возвращает `false`, когда новый
player/metric key не помещается в capacity, и producers не восстанавливают такую
мутацию. Теперь каждый отказ атомарно увеличивает cumulative `rejectedCount()`;
`ProgressBufferTest` закрепляет счётчик вместе с capacity rejection.

Health contribution выставляет `DOWN` после первого отказа, публикует текущий
`pendingCount()` как recovery backlog и dependency
`progress_buffer_no_rejections=false`. Потеря больше не бесшумна для health
monitoring, но rejected mutation всё ещё не попадает ни в pending, ни в storage.

**Открытый остаток:** определить producer/backpressure policy для уже отклонённой
мутации и добавить rate-limited WARN без кардинальности по игрокам. Платформенная
регрессия должна заполнить capacity, послать реальное Bukkit event/periodic sample
и проверить одновременно drop, счётчик и DOWN health.

### AR-011 — P3 — late join-load возвращает офлайн-игрока в cache

**Статус: исправлено в текущем source и покрыто service unit-тестами.**

`RankSnapshotCache` выдаёт async load токен, связанный с global и per-player
generation. `remove`, `clear`, `put` и perk update инвалидируют старые токены;
`RankPlayerService.load/selectFocus` публикуют результат только через
`putIfCurrent`. Late completion после quit или reload больше не может вернуть
устаревший snapshot.

`RankPlayerServiceTest` покрывает late load после player invalidation, late load
после global clear, delayed `selectFocus`, current token и новые session tokens.
MockBukkit join → quit event wiring остаётся полезной дополнительной проверкой.

### AR-012 — P3 — результат выдачи weekly kit теряется при нажатии Back

**Статус: исправлено в текущем source; платформенная регрессия ещё не добавлена.**

Выбрана политика «результат доставляется независимо от навигации».
`WeeklyKitMenu.claim()` вычисляет telemetry/locale result и отправляет сообщение до
проверки `holder.current`; только mutation/refresh старого inventory защищены
generation check. Та же безопасная политика применена к persistent actions в
perk и contract menus.

Нужен MockBukkit сценарий claim → Back → delayed `Claimed`/`DeliveryPending`, чтобы
зафиксировать event wiring и отсутствие перерисовки уже закрытого menu.

### AR-013 — P3 — PlaceholderAPI игнорирует client locale

**Статус: исправлено в текущем source и покрыто MockBukkit-сценарием; финальный
общий test gate ещё впереди.**

`ArcRanksPlaceholderExpansion.onRequest()` теперь приводит online
`OfflinePlayer` к `Player` и передаёт его как audience при рендеринге
`rank_name`/`next_rank`. `ArcRanksPlaceholderExpansionMockBukkitTest` включает
`use-client-locale`, создаёт игроков `ru-RU` и `en-US` с одинаковым snapshot и
проверяет пары `Поселенец`/`Settler` и `Крестьянин`/`Peasant`.

### AR-014 — P3 — runtime-профили не задают общий Back asset

**Статус: source-ready, rollout не выполнен.**

Оба активных профиля:

- `.deploy-ruscrafting-ops/classic/plugins/ArcRanks/config.yml:39-48`;
- `.deploy-ruscrafting-ops/classic_survival/plugins/ArcRanks/config.yml:39-48`

задают shared background CMD 11000, но не `gui.back`. Bundled fallback теперь
исправлен на `BLUE_STAINED_GLASS_PANE`, CMD 11013 (`arc:left_gray`), и
MockBukkit-навигация проверяет точный `custom-model-data`. Однако runtime-профили
и production в этой работе не изменялись: требуется обычный reviewed rollout и
визуальная проверка фактически materialized runtime config.

### AR-015 — P3 — дробный perk-бонус сбрасывается между сессиями

**Статус: открыт; требуется продуктовая политика.**

`FractionalProgressBonus` копит остаток в памяти, а quit вызывает
`modifier.clear()`. Игрок с небольшими короткими сессиями может постоянно не
достигать целого bonus unit, хотя суммарно должен был его получить.

Это требует продуктового решения: сохранять remainder, округлять по другой
политике либо явно принять session-scoped rounding. До решения это риск
справедливости, а не подтверждённая потеря базового прогресса.

## Дополнительные findings конфигурируемости и reload

### AR-016 — P1 — любой live reload сбрасывает частично накопленный sample interval

**Статус: исправлено в текущем source и покрыто unit-тестами.**

Finding обнаружен дополнительным review уже после первого reload refactor. Он
закрыт тем же stable heartbeat и `DynamicTickCadence`, что описаны в AR-004:
обычный reload не сбрасывает elapsed ticks, shortening выпускает накопленный
interval, а lengthening сохраняет carry до новой границы. Даже health-period
reload переустанавливает runtime tasks без пересоздания трёх cadence objects.
Для sampling в service передаётся именно число накопленных минут, а не всегда
один жёсткий interval.

### AR-017 — P1 — reload создаёт окно без активных perk-бонусов

**Статус: исправлено в текущем source и покрыто service unit-тестом.**

`RankSnapshotCache` теперь отдельно хранит last-known `PerkProgressContext`.
Config reload инвалидирует derived rank snapshots только для rank/mastery areas,
но сохраняет perk/progress inputs до успешного warmup. Progress modifier
дополнительно пересчитывает допустимые эффекты по текущим perk/mastery configs.
Quit и полный lifecycle cleanup по-прежнему удаляют оба cache слоя.

`RankPlayerServiceTest` проверяет, что `invalidateSnapshots()` очищает GUI snapshot,
но сохраняет last-known perk progress context.

### AR-018 — P1 — candidate может смешать поколения файлов и fingerprints

**Статус: исправлено в текущем source; отдельная race-регрессия ещё желательна.**

Loader вычисляет полный набор SHA-256 до parse и после validation. Несовпадение
отклоняет candidate с просьбой повторить reload, поэтому snapshot со смешанными
поколениями не публикуется и не получает новый fingerprint. Парсинг immutable
набора bytes был бы ещё строже, но исходный false-`NoChanges` path закрыт.

### AR-019 — P2 — reload mastery не пересматривает уже выбранные perks

**Статус: исправлено в текущем source и покрыто unit-тестом.**

Выбрана политика «selection сохраняется, недоступный effect не применяется».
Перед каждым progress bonus текущий catalog фильтрует active perk IDs через
текущий player profile и текущие mastery thresholds. Повышение требования на
reload немедленно исключает эффект без разрушительной перезаписи выбранного
слота; после достижения mastery тот же выбор снова становится активным.

`PerkProgressModifierTest` проверяет один и тот же выбранный perk до и после
reload thresholds: сначала eligible, затем пустой effect set.

### AR-020 — P2 — изменение только `logging.yml` reload не замечает

**Статус: исправлено в текущем source и покрыто четырьмя unit-сценариями.**

Arc-core-owned `logging.yml` отслеживает отдельный `ArcRanksLoggingReloader`.
Logging-only edit превращает core `NoChanges` в `Applied(LOGGING)`, unchanged bytes
остаются no-op, ошибка не продвигает active fingerprint, а mutation файла во время
reload отклоняется. `ArcRanksLoggingReloaderTest` проверяет все четыре случая.

### AR-021 — P3 — уже запланированный firework заканчивается по старому snapshot

**Статус: исправлено в текущем source; отдельный delayed scheduler test ещё нужен.**

Перед каждым delayed spawn callback сравнивает захваченные celebration settings с
текущими. Любое live изменение, включая `enabled: false`, отменяет старый firework;
несвязанный reload с эквивалентными settings не прерывает уже начатую церемонию.

### AR-022 — P2 — `gui.items` не требует точного набора ключей

**Статус: исправлено в текущем source и покрыто unit-регрессией.**

`GuiSettings` требует точного равенства configured keys и поддерживаемого набора.
При missing/unknown key candidate отклоняется и перечисляет обе группы, поэтому
опечатка больше не выглядит как успешный GUI reload. Code fallbacks остаются
локальной защитой конструктора menu, но bundled/runtime config обязан быть полным.
`ArcRanksConfigurationTest` одновременно удаляет правильный key, добавляет
опечатанный и проверяет, что isolated candidate перечисляет оба пути без секрета.

### AR-023 — P2 — нет live feature gates для contracts, perks и weekly kits

**Статус: исправлено в текущем source и покрыто MockBukkit boundary-сценарием.**

Live `features.contracts`, `features.perks` и `features.weekly-kits` отклоняют
новый вход до inventory/storage work и показывают локализованную причину. Reload
закрывает уже открытые ArcRanks menus, а отключение perks немедленно исключает их
из progress modifier. `FeatureGateMenusMockBukkitTest` проверяет все три entry
guards и ноль storage calls.

### AR-024 — P2 — collection sources не имеют material allow/deny filters

**Статус: исправлено в текущем source; отдельная event-регрессия ещё желательна.**

Block place, crafting и furnace получили live `included-materials` и
`excluded-materials`. Empty include означает все материалы, exclude имеет
приоритет; пересечение и небезопасные/неподходящие Bukkit material names
отклоняют candidate. Listener применяет filter до записи progress mutation.

### AR-025 — P2 — доступность specialization paths не управляется конфигом

**Статус на момент дополнительного review: открыт.**

Список unavailable paths формируется один раз только по наличию Vault; нельзя
закрыть путь на конкретном backend или временно отключить его. Live
`paths.disabled` должен объединяться с обязательным отключением TRADE при
отсутствии Economy.

### AR-026 — P2 — contracts и weekly kits используют разные границы недели

**Статус: открыт.**

Contracts начинают неделю в Monday UTC, weekly kits — Monday Europe/Moscow.
Каждую неделю в течение трёх часов подсистемы показывают разные cycles. Следует
вынести общий validated `weekly-cycle.zone-id/start-day`; смену границы безопаснее
делать restart-only либо применять только со следующего cycle.

### AR-027 — P3 — initial focus жёстко задан как FARMING

**Статус: открыт.**

Новый профиль всегда получает FARMING. Live `profile.default-focus` должен быть
валидным и доступным path, а repository — получать настройку через provider.

### AR-028 — P3 — остаются полезные presentation knobs

**Статус: исправлено частично.**

Sound category и firework flicker вынесены в live celebration config и
валидируются как bounded enums/booleans. Формат weekly date всё ещё выбирается
кодом, поэтому presentation configurability здесь остаётся неполной.

### AR-029 — P3 — в analytics остался старый фиксированный список окон

**Статус: открыт как cleanup/test debt.**

Runtime menu уже читает windows из YAML, но неиспользуемая константа и старый
contract test продолжают закреплять `7/14/30`. Их следует удалить или заменить
проверкой динамических windows/default через config store, чтобы тесты не
противоречили production path.

### AR-030 — P2 — in-flight perk action смешивает поколения definition и locale

**Статус: исправлено в текущем source; отдельная delayed action-регрессия ещё
желательна.**

`PerkMenu.choose()` захватывает `RankLocale` поколения действия до запуска
persistent future и использует тот же объект и для result path, и для
`PerkDefinition.nameKey`. Одновременная смена perk name-key/locale больше не
рендерит старое определение через новый несовместимый locale catalog.

### AR-031 — P2 — stale reload warmup может затереть новую perk selection

**Статус: исправлено в текущем source и покрыто unit-регрессией.**

Раньше `RankSnapshotCache.updatePerks()` менял per-player generation только при
наличии derived snapshot. После `invalidateSnapshots()` reload warmup мог
захватить token, затем новая perk mutation обновляла last-known context без его
rotation; поздний warmup оставался current и записывал старую selection поверх неё.

Теперь `updatePerks()` безусловно ротирует player token до обновления обоих слоёв cache.
Сценарий `perk mutation wins over a stale reload warmup` запускает delayed load
после snapshot invalidation, применяет новую selection и проверяет, что late
completion не публикуется, а новый perk context сохраняется.

### AR-032 — P2 — weekly-kit icons не проходили центральную item-валидацию

**Статус: исправлено в текущем source и покрыто unit-регрессией.**

Live weekly-kit definitions участвовали в snapshot, но их `icon` не проверялся
общим Bukkit item predicate, которым уже валидировались GUI assets. Невалидный
material мог пройти reload и упасть только при следующем render меню.

`ArcRanksConfigLoader` теперь применяет тот же `Material.matchMaterial`/`isItem`
guard к каждому `weekly-kits.<rank>.icon`. Сценарий
`invalid live weekly kit icon rejects the isolated candidate` подставляет
несуществующий material и проверяет точный config path без утечки секрета.

## Добавленные MockBukkit- и reload-регрессии

Файл: `src/test/kotlin/ru/ruscrafting/ranks/gui/RankPassportMenuMockBukkitTest.kt`

Каждый случай:

- открывает отдельный `MockBukkitTestRuntime` и закрывает его через `use`;
- целиком обёрнут в `failOnUnsupportedMockBukkitOperation`, поэтому unsupported API не превратит тест в ложный skip;
- использует реальный `PlayerMock`, Bukkit inventory, зарегистрированный listener, `PluginManager.callEvent` и детерминированные scheduler ticks;
- использует реальные bundled config/ranks/lang и реальный `RankMenuItemFactory`;
- мокает только storage-facing `RankPlayerService` и `PromotionService`, чтобы не добавлять `testMode` в production и не поднимать LuckPerms/MySQL для GUI boundary.

Локальная документация актуального исходника arc-core уже предоставляет общий
`ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation`, но реально разрешённый
для ArcRanks опубликованный artifact `arc-core-paper-testing:2.1.3` его ещё не
экспортирует и транзитивно фиксирует MockBukkit 4.110.0, тогда как соседний исходник
arc-core уже перешёл на более новый контракт. Это проверено по POM и bytecode JAR.
Поэтому набор содержит приватную точную копию guard. После обновления arc-core её
следует заменить общим import.

### MB-001 — loading → rendered overview

Проверяет:

1. немедленный loading state;
2. async completion через `LifecycleTaskScope` после одного tick;
3. реальный title и размер inventory 45 слотов;
4. материалы profile/contracts/paths/perks/weekly-kit/promotion/benefits;
5. явный `ITALIC = FALSE` у display name и каждой lore line всех заполненных слотов;
6. ровно одну загрузку snapshot.

### MB-002 — ownership, click, drag, paths, Back

Проверяет:

1. отмену клика в верхнем ArcRanks inventory;
2. отмену shift/click из нижнего inventory при открытом меню;
3. отмену drag, пересекающего верхний inventory;
4. вызов navigation callback через реальный click event;
5. переход Overview → Paths;
6. реальный Back item из config и его nonitalic surface;
7. переход Paths → Overview;
8. отсутствие вмешательства в посторонний inventory и его drag/click.

### MB-003 — stale async completion

Проверяет последовательность: открыть паспорт с delayed snapshot → уйти в другой inventory → завершить snapshot → выполнить scheduler tick. Старый callback не имеет права заменить или перерисовать текущий inventory.

### MB-004 — permission gate повышения

Проверяет реальный promotion click у не-op `PlayerMock` с
`arcranks.use=true` и `arcranks.rankup=false`: event отменён, service не вызван,
игрок получает локализованный `commands.no-permission`.

### MB-005 — сериализация focus selection

Проверяет два быстрых path click при незавершённом первом future. Принята
first-intent политика: первый путь вызывает service ровно один раз, второй не
запускается, а после completion игрок получает одно согласованное сообщение.

### MB-006 — закрытие ArcRanks sessions при reload

Файл: `src/test/kotlin/ru/ruscrafting/ranks/gui/ArcRanksMenuSessionsMockBukkitTest.kt`

Один реальный MockBukkit сценарий открывает ArcRanks inventory с typed holder и
посторонний inventory у второго игрока. `ArcRanksMenuSessions.closeOpen()`
закрывает только ArcRanks session, сохраняет чужой inventory и при повторном
вызове возвращает ноль.

### MB-007 — client-locale PlaceholderAPI

Файл:
`src/test/kotlin/ru/ruscrafting/ranks/placeholder/ArcRanksPlaceholderExpansionMockBukkitTest.kt`

Один MockBukkit сценарий включает client locale, создаёт русский и английский
`PlayerMock`, кладёт эквивалентные snapshots в cache и проверяет локализованные
`rank_name` и `next_rank` для обоих audiences.

### MB-008 — live feature gates

Файл: `src/test/kotlin/ru/ruscrafting/ranks/gui/FeatureGateMenusMockBukkitTest.kt`

Один MockBukkit сценарий выключает contracts, perks и weekly kits, вызывает три
menu entry points, проверяет локализованные сообщения и отсутствие загрузки
player snapshot, contract board и weekly-kit state.

### RU-001—RU-006 — координатор reload

Файл: `src/test/kotlin/ru/ruscrafting/ranks/reload/ArcRanksReloadCoordinatorTest.kt`

Шесть unit-сценариев проверяют:

1. byte-identical no-op без смены wrapper и вызова lifecycle hooks;
2. invalid candidate без изменения active generation и вызова hooks;
3. точный restart-only result без частичного применения;
4. publish live snapshot только после успешного candidate hook;
5. generic rollback hook и неизменный store при ошибке candidate hook;
6. `Busy` для конкурентного reload, пока первый ещё парсит candidate.

Это проверка reusable seam самого координатора. В production restart hook сам по
себе no-op, а install hook делегирует `ArcRanksRecurringTasks.install()`. При
неизменном `runtime.health-report-ticks` install тоже no-op, и существующие задачи
продолжают читать active snapshot. При смене health period install через
`runtime.reload()` транзакционно переустанавливает gameplay heartbeat и стандартный
health task; при неуспешной установке или CAS generic rollback seam возвращает
старый health period. Долгоживущие cadence objects при этом сохраняют carry.

Дополнительно `ArcRanksConfigurationTest` проверяет стабильные SHA-256
fingerprints без секрета, изоляцию повреждённого locale, классификацию live
областей, агрегацию точных restart-only paths без раскрытия пароля и отказ при
missing/unknown `gui.items` keys или невалидном live weekly-kit icon.

`ArcRanksLoggingReloaderTest` добавляет четыре unit-сценария independent
`logging.yml` reload: unchanged, changed-once, retryable failure и concurrent
file mutation. `DynamicTickCadenceTest` тремя сценариями закрепляет carry при
обычном, сокращённом и увеличенном live period.

## Оставшаяся MockBukkit-матрица

Ниже только ещё не закрытые платформенные границы; уже реализованные permission
и focus сценарии из будущей матрицы удалены:

| ID | Сценарий | Платформенные действия | Главные assertions |
|---|---|---|---|
| MB-G01 | Recovery через GUI | saga APPLIED + target snapshot не READY | recovery вызывается несмотря на eligibility следующего ранга |
| MB-G02 | Weekly kit pending navigation | claim click, Back, delayed result | сообщение не теряется, закрытый inventory не перерисовывается |
| MB-G03 | Join/quit cache wiring | join event, quit event, late future | cache не содержит офлайн UUID; дополняет service unit-тесты |
| MB-G04 | Progress/backpressure | допустимые и запрещённые Bukkit events + full buffer | collection policy соблюдена; drop наблюдаем и rate-limited |
| MB-G05 | Reload scheduling wiring | gameplay/health tasks, live period changes, ticks | unrelated reload не меняет handles; health-period reload переустанавливает ровно обе runtime-задачи; carry трёх gameplay cadences сохраняется без дублей |
| MB-G06 | Celebration | title/sound/particles/firework damage | эффекты по profile, firework не наносит урон, recovery без дубля |
| MB-G07 | Lifecycle cleanup | open menus + pending callbacks + disable | callbacks/listeners/tasks не мутируют закрытый epoch |

## Что остаётся integration-тестами

MockBukkit не заменяет реальные MySQL и LuckPerms semantics. Следующие проверки должны оставаться в `integrationTest` или consumer-verifier:

- сохранение expiry/contexts и атомарность LuckPerms mutation;
- promotion saga при падениях между PREPARED/APPLIED/COMPLETED;
- barrier-семантика `flushAll` при настоящем executor/закрытии SQL;
- weekly-kit lease/reconciliation и межсерверная гонка;
- идемпотентность external progress event;
- миграции и ограничения MySQL 8.

Финальный локальный gate
`./gradlew --no-daemon test compileIntegrationTestKotlin shadowJar` завершился
`BUILD SUCCESSFUL` за 30 секунд: 150 tests, 0 failures, 0 errors, 0 skipped.
Consumer architecture verifier завершился с `status=ok`. Canonical visual preview
также дал `status=ok`: 378/378 назначенных поверхностей в 138 файлах,
`automatic_chat_wraps=0` и ни одного unresolved placeholder. Preview contract
явно назначает `features.*` и задаёт значения placeholders.

Сам `integrationTest` локально не запускался: проект прямо запрещает поднимать
Docker/MySQL на рабочей машине. Фактические SQL-сценарии должны выполняться в CI;
успешный `compileIntegrationTestKotlin` подтверждает их компиляцию, но не заменяет
выполнение против MySQL.

## Рекомендуемый порядок исправлений

1. AR-007: спроектировать lease/reconciliation для внешней выдачи weekly kit и
   доказать crash-window semantics в MySQL integration suite.
2. AR-010: поверх уже работающих rejection counter и DOWN health определить
   судьбу отклонённой мутации, поведение producers и rate-limited operator WARN.
3. AR-025: добавить live disabled specialization paths с обязательным TRADE
   fallback при отсутствии Vault economy.
4. AR-026: унифицировать границу недели contracts/weekly kits и проверить
   миграционную semantics уже открытого цикла.
5. AR-027 и AR-028: вынести default focus и weekly date format с валидацией.
6. AR-029: удалить устаревшую analytics windows константу и заменить старый
   contract test динамической config-проверкой.
7. AR-014: провести reviewed rollout bundled Back asset в runtime-профили и
   визуально проверить materialized config на обоих backend.
8. AR-015: принять продуктовую политику remainder и только затем выбирать
   persistence либо другое детерминированное округление.
9. Закрыть оставшиеся verification gaps для уже исправленных AR-002, AR-004,
   AR-006, AR-009, AR-011, AR-012, AR-018, AR-021, AR-024 и AR-030 без повторного
   открытия их старых code paths.

Production-код в текущем worktree существенно изменён: добавлены исправления и
конфигурируемый атомарный hot reload. При этом deployment, production database,
player data и runtime-профили не менялись; rollout остаётся отдельной reviewed
операцией.
