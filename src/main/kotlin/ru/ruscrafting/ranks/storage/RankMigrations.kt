package ru.ruscrafting.ranks.storage

import ru.arc.sql.SqlMigration

object RankMigrations {
    val ALL: List<SqlMigration> = listOf(
        SqlMigration(
            version = 1,
            description = "Create ArcRanks progress, profile and external event tables",
            statements = listOf(
                """
                CREATE TABLE IF NOT EXISTS `arc_ranks_progress` (
                    `player_uuid` CHAR(36) NOT NULL,
                    `metric` VARCHAR(40) NOT NULL,
                    `value` BIGINT UNSIGNED NOT NULL,
                    `updated_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
                    PRIMARY KEY (`player_uuid`, `metric`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS `arc_ranks_profile` (
                    `player_uuid` CHAR(36) NOT NULL,
                    `selected_focus` VARCHAR(32) NOT NULL,
                    `updated_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
                    PRIMARY KEY (`player_uuid`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS `arc_ranks_progress_events` (
                    `source` VARCHAR(40) NOT NULL,
                    `event_id` VARCHAR(120) NOT NULL,
                    `player_uuid` CHAR(36) NOT NULL,
                    `metric` VARCHAR(40) NOT NULL,
                    `delta` BIGINT UNSIGNED NOT NULL,
                    `created_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                    PRIMARY KEY (`source`, `event_id`),
                    KEY `idx_arc_ranks_events_player` (`player_uuid`, `created_at`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """.trimIndent(),
            ),
        ),
        SqlMigration(
            version = 2,
            description = "Create recoverable ArcRanks promotion saga tables",
            statements = listOf(
                """
                CREATE TABLE IF NOT EXISTS `arc_ranks_promotion_state` (
                    `player_uuid` CHAR(36) NOT NULL,
                    `generation` BIGINT UNSIGNED NOT NULL,
                    `from_rank` VARCHAR(40) NOT NULL,
                    `target_rank` VARCHAR(40) NOT NULL,
                    `state` VARCHAR(24) NOT NULL,
                    `updated_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
                    PRIMARY KEY (`player_uuid`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS `arc_ranks_promotion_history` (
                    `history_id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
                    `player_uuid` CHAR(36) NOT NULL,
                    `generation` BIGINT UNSIGNED NOT NULL,
                    `from_rank` VARCHAR(40) NOT NULL,
                    `target_rank` VARCHAR(40) NOT NULL,
                    `state` VARCHAR(24) NOT NULL,
                    `created_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                    PRIMARY KEY (`history_id`),
                    KEY `idx_arc_ranks_history_player` (`player_uuid`, `generation`, `history_id`),
                    KEY `idx_arc_ranks_history_created` (`created_at`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """.trimIndent(),
            ),
        ),
        SqlMigration(
            version = 3,
            description = "Create idempotent ArcRanks product analytics rollups",
            statements = listOf(
                """
                CREATE TABLE IF NOT EXISTS `arc_ranks_analytics_batch` (
                    `batch_id` CHAR(36) NOT NULL,
                    `server_id` VARCHAR(40) NOT NULL,
                    `bucket_start` DATETIME(0) NOT NULL,
                    `created_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                    PRIMARY KEY (`batch_id`),
                    KEY `idx_arc_ranks_analytics_batch_created` (`created_at`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS `arc_ranks_product_metric_hour` (
                    `bucket_start` DATETIME(0) NOT NULL,
                    `server_id` VARCHAR(40) NOT NULL,
                    `event` VARCHAR(40) NOT NULL,
                    `dimension` VARCHAR(80) NOT NULL,
                    `value` BIGINT UNSIGNED NOT NULL,
                    `updated_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
                    PRIMARY KEY (`bucket_start`, `server_id`, `event`, `dimension`),
                    KEY `idx_arc_ranks_product_event_time` (`event`, `bucket_start`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS `arc_ranks_product_player_day` (
                    `day` DATE NOT NULL,
                    `player_uuid` CHAR(36) NOT NULL,
                    `latest_rank` VARCHAR(40) NULL,
                    `seen` TINYINT(1) NOT NULL DEFAULT 0,
                    `passport_opened` TINYINT(1) NOT NULL DEFAULT 0,
                    `contract_accepted` TINYINT(1) NOT NULL DEFAULT 0,
                    `contract_completed` TINYINT(1) NOT NULL DEFAULT 0,
                    `perk_selected` TINYINT(1) NOT NULL DEFAULT 0,
                    `updated_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
                    PRIMARY KEY (`day`, `player_uuid`),
                    KEY `idx_arc_ranks_product_player` (`player_uuid`, `day`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """.trimIndent(),
            ),
        ),
        SqlMigration(
            version = 4,
            description = "Create transactional ArcRanks perk selections",
            statements = listOf(
                """
                CREATE TABLE IF NOT EXISTS `arc_ranks_perk_owner` (
                    `player_uuid` CHAR(36) NOT NULL,
                    `updated_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
                    PRIMARY KEY (`player_uuid`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS `arc_ranks_perk_selection` (
                    `player_uuid` CHAR(36) NOT NULL,
                    `slot` TINYINT UNSIGNED NOT NULL,
                    `perk_id` VARCHAR(48) NOT NULL,
                    `updated_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
                    PRIMARY KEY (`player_uuid`, `slot`),
                    UNIQUE KEY `uq_arc_ranks_perk_player_id` (`player_uuid`, `perk_id`),
                    CONSTRAINT `chk_arc_ranks_perk_slot` CHECK (`slot` BETWEEN 1 AND 2)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """.trimIndent(),
            ),
        ),
        SqlMigration(
            version = 5,
            description = "Create weekly ArcRanks personal contracts",
            statements = listOf(
                """
                CREATE TABLE IF NOT EXISTS `arc_ranks_contract_cycle` (
                    `player_uuid` CHAR(36) NOT NULL,
                    `cycle_start` DATE NOT NULL,
                    `generation` TINYINT UNSIGNED NOT NULL DEFAULT 0,
                    `reroll_nonce` TINYINT UNSIGNED NOT NULL DEFAULT 0,
                    `updated_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
                    PRIMARY KEY (`player_uuid`),
                    CONSTRAINT `chk_arc_ranks_contract_generation` CHECK (`generation` BETWEEN 0 AND 3),
                    CONSTRAINT `chk_arc_ranks_contract_reroll` CHECK (`reroll_nonce` BETWEEN 0 AND 1)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS `arc_ranks_contract` (
                    `contract_id` CHAR(24) NOT NULL,
                    `player_uuid` CHAR(36) NOT NULL,
                    `cycle_start` DATE NOT NULL,
                    `generation` TINYINT UNSIGNED NOT NULL,
                    `path` VARCHAR(32) NOT NULL,
                    `metric` VARCHAR(40) NOT NULL,
                    `baseline` BIGINT UNSIGNED NOT NULL,
                    `target_delta` BIGINT UNSIGNED NOT NULL,
                    `reward_delta` BIGINT UNSIGNED NOT NULL,
                    `state` VARCHAR(16) NOT NULL,
                    `accepted_at` TIMESTAMP(3) NOT NULL,
                    `claimed_at` TIMESTAMP(3) NULL,
                    `expired_at` TIMESTAMP(3) NULL,
                    PRIMARY KEY (`contract_id`),
                    UNIQUE KEY `uq_arc_ranks_contract_generation` (`player_uuid`, `cycle_start`, `generation`),
                    KEY `idx_arc_ranks_contract_active` (`player_uuid`, `state`, `cycle_start`),
                    CONSTRAINT `chk_arc_ranks_contract_state` CHECK (`state` IN ('ACTIVE', 'CLAIMED', 'EXPIRED'))
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """.trimIndent(),
            ),
        ),
    )
}
