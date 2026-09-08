package ru.ruscrafting.ranks.storage

import ru.arc.sql.SqlMigration
import ru.arc.sql.onetime.MySqlOneTimeUseLedger

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
        SqlMigration(
            version = 6,
            description = "Create cross-server weekly rank kit claims",
            statements = listOf(
                """
                CREATE TABLE IF NOT EXISTS `arc_ranks_weekly_kit_claim` (
                    `player_uuid` CHAR(36) NOT NULL,
                    `cycle_start` DATE NOT NULL,
                    `claim_id` CHAR(36) NOT NULL,
                    `rank_id` VARCHAR(40) NOT NULL,
                    `kit_id` VARCHAR(64) NOT NULL,
                    `server_id` VARCHAR(40) NOT NULL,
                    `state` VARCHAR(16) NOT NULL,
                    `claimed_at` TIMESTAMP(3) NULL,
                    `updated_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
                    PRIMARY KEY (`player_uuid`, `cycle_start`),
                    UNIQUE KEY `uq_arc_ranks_weekly_claim_id` (`claim_id`),
                    KEY `idx_arc_ranks_weekly_updated` (`state`, `updated_at`),
                    CONSTRAINT `chk_arc_ranks_weekly_state` CHECK (`state` IN ('DELIVERING', 'CLAIMED'))
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """.trimIndent(),
            ),
        ),
        SqlMigration(
            version = 7,
            description = "Add auditable admin completion for personal contracts",
            statements = listOf(
                """
                ALTER TABLE `arc_ranks_contract`
                    ADD COLUMN `admin_completed_at` TIMESTAMP(3) NULL AFTER `accepted_at`,
                    ADD COLUMN `admin_completed_by` VARCHAR(64) NULL AFTER `admin_completed_at`
                """.trimIndent(),
            ),
        ),
        SqlMigration(
            version = 8,
            description = "Persist recoverable money, token and item rewards for personal contracts",
            statements = listOf(
                """
                ALTER TABLE `arc_ranks_contract`
                    ADD COLUMN `money_reward` BIGINT UNSIGNED NOT NULL DEFAULT 2000 AFTER `reward_delta`,
                    ADD COLUMN `token_reward` BIGINT UNSIGNED NOT NULL DEFAULT 1 AFTER `money_reward`,
                    ADD COLUMN `token_currency` VARCHAR(16) NOT NULL DEFAULT 'tokens' AFTER `token_reward`,
                    ADD COLUMN `item_preset` VARCHAR(64) NOT NULL DEFAULT 'enchant_token' AFTER `token_currency`,
                    ADD COLUMN `item_amount` TINYINT UNSIGNED NOT NULL DEFAULT 1 AFTER `item_preset`,
                    ADD COLUMN `reward_delivery_state` VARCHAR(16) NOT NULL DEFAULT 'GRANTED' AFTER `state`,
                    ADD COLUMN `reward_delivered_at` TIMESTAMP(3) NULL AFTER `claimed_at`,
                    ADD COLUMN `reward_failure_code` VARCHAR(64) NULL AFTER `reward_delivered_at`,
                    ADD KEY `idx_arc_ranks_contract_reward` (`player_uuid`, `reward_delivery_state`, `claimed_at`),
                    ADD CONSTRAINT `chk_arc_ranks_contract_reward_state`
                        CHECK (`reward_delivery_state` IN ('PENDING', 'GRANTED', 'RECOVERY'))
                """.trimIndent(),
                """
                UPDATE `arc_ranks_contract`
                SET `money_reward` = CASE `generation` WHEN 0 THEN 2000 WHEN 1 THEN 3500 ELSE 5000 END,
                    `token_reward` = CASE `generation` WHEN 0 THEN 1 WHEN 1 THEN 2 ELSE 3 END,
                    `item_preset` = CASE `generation`
                        WHEN 0 THEN 'enchant_token'
                        WHEN 1 THEN 'potion_token'
                        ELSE 'sf_lootbox'
                    END,
                    `reward_delivery_state` = 'PENDING'
                WHERE `state` = 'ACTIVE'
                """.trimIndent(),
            ),
        ),
        MySqlOneTimeUseLedger.createTableMigration(version = 9),
        SqlMigration(
            version = 10,
            description = "Audit administrator resets of confirmed weekly kits",
            statements = listOf(
                """
                CREATE TABLE IF NOT EXISTS `arc_ranks_weekly_kit_admin_reset` (
                    `reset_id` CHAR(36) NOT NULL,
                    `player_uuid` CHAR(36) NOT NULL,
                    `cycle_start` DATE NOT NULL,
                    `claim_id` CHAR(36) NOT NULL,
                    `rank_id` VARCHAR(40) NOT NULL,
                    `kit_id` VARCHAR(64) NOT NULL,
                    `server_id` VARCHAR(40) NOT NULL,
                    `admin_actor` VARCHAR(64) NOT NULL,
                    `reset_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                    PRIMARY KEY (`reset_id`),
                    KEY `idx_arc_ranks_weekly_reset_player` (`player_uuid`, `cycle_start`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """.trimIndent(),
            ),
        ),
        SqlMigration(
            version = 11,
            description = "Add bounded cross-server daily quest progress",
            statements = listOf(
                """
                CREATE TABLE IF NOT EXISTS `arc_ranks_daily_quest` (
                    `player_uuid` CHAR(36) NOT NULL,
                    `quest_id` VARCHAR(32) NOT NULL,
                    `quest_day` DATE NOT NULL,
                    `value` BIGINT UNSIGNED NOT NULL DEFAULT 0,
                    PRIMARY KEY (`player_uuid`, `quest_id`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """.trimIndent(),
            ),
        ),
        SqlMigration(
            version = 12,
            description = "Freeze ranked daily assignments and persist currency obligations",
            statements = listOf(
                """CREATE TABLE IF NOT EXISTS arc_ranks_daily_board (
                    player_uuid CHAR(36) NOT NULL PRIMARY KEY, quest_day DATE NOT NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci""",
                """CREATE TABLE IF NOT EXISTS arc_ranks_daily_goal (
                    player_uuid CHAR(36) NOT NULL, position TINYINT UNSIGNED NOT NULL,
                    quest_id VARCHAR(32) NOT NULL, reward_id CHAR(24) NOT NULL,
                    metric VARCHAR(40) NOT NULL, objective VARCHAR(96) NOT NULL, target BIGINT UNSIGNED NOT NULL, bonus BIGINT UNSIGNED NOT NULL,
                    material VARCHAR(40) NOT NULL, text_id VARCHAR(32) NOT NULL,
                    money BIGINT UNSIGNED NOT NULL, tokens BIGINT UNSIGNED NOT NULL, token_currency VARCHAR(16) NOT NULL,
                    value BIGINT UNSIGNED NOT NULL DEFAULT 0,
                    PRIMARY KEY (player_uuid, position), UNIQUE KEY uq_daily_goal (player_uuid, quest_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci""",
                """CREATE TABLE IF NOT EXISTS arc_ranks_quest_event (
                    source VARCHAR(40) NOT NULL, event_id VARCHAR(120) NOT NULL, player_uuid CHAR(36) NOT NULL,
                    objective VARCHAR(96) NOT NULL, amount BIGINT UNSIGNED NOT NULL,
                    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                    PRIMARY KEY (source, event_id), KEY idx_quest_event_created (created_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci""",
                """CREATE TABLE IF NOT EXISTS arc_ranks_daily_reward (
                    reward_id CHAR(24) NOT NULL PRIMARY KEY, player_uuid CHAR(36) NOT NULL,
                    money BIGINT UNSIGNED NOT NULL, tokens BIGINT UNSIGNED NOT NULL, token_currency VARCHAR(16) NOT NULL,
                    state VARCHAR(16) NOT NULL, failure_code VARCHAR(64) NULL,
                    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                    KEY idx_daily_reward_pending (player_uuid, state, created_at),
                    CONSTRAINT chk_daily_reward_state CHECK (state IN ('PENDING', 'GRANTED', 'RECOVERY'))
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci""",
            ),
        ),
        SqlMigration(
            version = 13,
            description = "Persist quest steps, daily replacements and one-time account goals",
            statements = addColumnIfMissing("arc_ranks_daily_board", "rank_id", "VARCHAR(40) NOT NULL DEFAULT 'settler'") +
                addColumnIfMissing("arc_ranks_daily_board", "scaling", "VARCHAR(80) NOT NULL DEFAULT '100,100,100,1'") +
                addColumnIfMissing("arc_ranks_daily_board", "replacement_limit", "TINYINT UNSIGNED NOT NULL DEFAULT 0") +
                addColumnIfMissing("arc_ranks_daily_board", "replacements", "TINYINT UNSIGNED NOT NULL DEFAULT 0") +
                addColumnIfMissing("arc_ranks_daily_goal", "quest_plan", "TEXT NULL") +
                addColumnIfMissing("arc_ranks_daily_goal", "step_values", "TEXT NULL") +
                addColumnIfMissing("arc_ranks_daily_goal", "quest_family", "VARCHAR(40) NOT NULL DEFAULT 'legacy'") +
                addColumnIfMissing("arc_ranks_daily_goal", "availability_key", "VARCHAR(96) NULL") +
                addColumnIfMissing("arc_ranks_daily_goal", "once_quest", "BOOLEAN NOT NULL DEFAULT FALSE") +
                addColumnIfMissing("arc_ranks_daily_goal", "scale_target", "BOOLEAN NOT NULL DEFAULT TRUE") +
                addColumnIfMissing("arc_ranks_daily_goal", "rare_eligible", "BOOLEAN NOT NULL DEFAULT TRUE") +
                addColumnIfMissing("arc_ranks_daily_goal", "challenge_suffix", "VARCHAR(32) NULL") +
                addColumnIfMissing("arc_ranks_daily_goal", "challenge_percent", "TINYINT UNSIGNED NOT NULL DEFAULT 0") +
                addColumnIfMissing("arc_ranks_daily_goal", "challenge_value", "BIGINT UNSIGNED NOT NULL DEFAULT 0") + listOf(
                """CREATE TABLE IF NOT EXISTS arc_ranks_quest_history (
                    player_uuid CHAR(36) NOT NULL, quest_day DATE NOT NULL, quest_id VARCHAR(32) NOT NULL,
                    PRIMARY KEY (player_uuid, quest_day, quest_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci""",
                """CREATE TABLE IF NOT EXISTS arc_ranks_quest_once (
                    player_uuid CHAR(36) NOT NULL, quest_id VARCHAR(32) NOT NULL,
                    completed_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                    PRIMARY KEY (player_uuid, quest_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci""",
            ),
        ),
        SqlMigration(
            version = 14,
            description = "Persist the optional tracked daily quest",
            statements = listOf("""CREATE TABLE IF NOT EXISTS arc_ranks_quest_tracking (
                player_uuid CHAR(36) NOT NULL PRIMARY KEY,
                quest_day DATE NOT NULL, quest_id VARCHAR(32) NOT NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"""),
        ),
        SqlMigration(
            version = 15,
            description = "Persist the player's quest display preference independently of daily pins",
            statements = listOf("""CREATE TABLE IF NOT EXISTS arc_ranks_quest_preferences (
                player_uuid CHAR(36) NOT NULL PRIMARY KEY, display_mode VARCHAR(16) NOT NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"""),
        ),
    )

    // MySQL commits DDL before schema history: every column must tolerate partial retries.
    private fun addColumnIfMissing(table: String, column: String, definition: String): List<String> {
        val ddl = "ALTER TABLE `$table` ADD COLUMN `$column` $definition".replace("'", "''")
        return listOf(
            """SET @arcranks_ddl = IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '$table' AND COLUMN_NAME = '$column'),
                'SELECT 1', '$ddl')""",
            "PREPARE arcranks_schema_stmt FROM @arcranks_ddl",
            "EXECUTE arcranks_schema_stmt",
            "DEALLOCATE PREPARE arcranks_schema_stmt",
        )
    }

}
