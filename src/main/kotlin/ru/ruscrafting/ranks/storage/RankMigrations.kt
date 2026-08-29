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
    )
}
