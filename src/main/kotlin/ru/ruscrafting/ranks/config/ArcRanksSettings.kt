package ru.ruscrafting.ranks.config

import ru.arc.config.ConfigManager
import ru.arc.sql.SqlConnectionConfig
import ru.arc.sql.SqlSslMode
import java.nio.file.Path

enum class PromotionMode {
    SHADOW,
    ACTIVE,
}

data class GuiItemSpec(
    val material: String,
    val customModelData: Int,
) {
    init {
        require(material.matches(Regex("[A-Z0-9_]{1,80}"))) { "Unsafe GUI material: $material" }
        require(customModelData >= 0) { "GUI custom model data must not be negative" }
    }
}

data class GuiSettings(
    val background: GuiItemSpec,
    val rankCompleted: GuiItemSpec,
    val rankCurrent: GuiItemSpec,
    val rankNext: GuiItemSpec,
    val path: GuiItemSpec,
    val promotion: GuiItemSpec,
)

data class ArcRanksSettings(
    val serverId: String,
    val promotionMode: PromotionMode,
    val defaultLocale: String,
    val useClientLocale: Boolean,
    val sql: SqlConnectionConfig,
    val maximumBufferEntries: Int,
    val flushTicks: Long,
    val sampleTicks: Long,
    val maximumIdleSeconds: Long,
    val maximumMovementStepBlocks: Double,
    val communityRadiusBlocks: Double,
    val gui: GuiSettings,
) {
    init {
        require(serverId.matches(Regex("[a-z0-9_-]{1,40}"))) { "Unsafe server-id: $serverId" }
        require(defaultLocale in setOf("ru", "en")) { "locale.default must be ru or en" }
        require(maximumBufferEntries in 64..100_000) { "progress.maximum-buffer-entries must be between 64 and 100000" }
        require(flushTicks >= 20) { "progress.flush-ticks must be at least 20" }
        require(sampleTicks >= 20) { "progress.sample-ticks must be at least 20" }
        require(sampleTicks % 1_200L == 0L) { "progress.sample-ticks must be a whole number of minutes" }
        require(maximumIdleSeconds in 60L..3_600L) { "progress.maximum-idle-seconds must be between 60 and 3600" }
        require(maximumMovementStepBlocks in 1.0..128.0) { "progress.maximum-movement-step-blocks must be between 1 and 128" }
        require(communityRadiusBlocks in 1.0..256.0) { "progress.community-radius-blocks must be between 1 and 256" }
    }

    companion object {
        fun load(
            dataRoot: Path,
            environment: (String) -> String? = System::getenv,
        ): ArcRanksSettings {
            val config = ConfigManager.of(dataRoot, "config.yml")
            val passwordEnvironment = config.string("mysql.password-env").trim()
            require(passwordEnvironment.matches(Regex("[A-Z][A-Z0-9_]{2,80}"))) {
                "mysql.password-env must name a safe environment variable"
            }
            val password = environment(passwordEnvironment)?.takeIf(String::isNotBlank)
                ?: error("Environment variable $passwordEnvironment is required")
            return ArcRanksSettings(
                serverId = config.string("server-id").trim().lowercase(),
                promotionMode = PromotionMode.valueOf(config.string("promotion-mode").trim().uppercase()),
                defaultLocale = config.string("locale.default").trim().lowercase(),
                useClientLocale = config.boolean("locale.use-client-locale"),
                sql = SqlConnectionConfig(
                    host = config.string("mysql.host").trim(),
                    port = config.int("mysql.port"),
                    database = config.string("mysql.database").trim(),
                    username = config.string("mysql.username").trim(),
                    password = password,
                    sslMode = SqlSslMode.valueOf(config.string("mysql.ssl-mode").trim().uppercase()),
                    minimumIdle = config.int("mysql.minimum-idle"),
                    maximumPoolSize = config.int("mysql.maximum-pool-size"),
                    connectionTimeoutMs = config.long("mysql.connection-timeout-ms"),
                    socketTimeoutMs = config.long("mysql.socket-timeout-ms"),
                    validationTimeoutMs = config.long("mysql.validation-timeout-ms"),
                    maxLifetimeMs = config.long("mysql.max-lifetime-ms"),
                    failFast = config.boolean("mysql.fail-fast"),
                ),
                maximumBufferEntries = config.int("progress.maximum-buffer-entries"),
                flushTicks = config.long("progress.flush-ticks"),
                sampleTicks = config.long("progress.sample-ticks"),
                maximumIdleSeconds = config.long("progress.maximum-idle-seconds"),
                maximumMovementStepBlocks = config.double("progress.maximum-movement-step-blocks"),
                communityRadiusBlocks = config.double("progress.community-radius-blocks"),
                gui = GuiSettings(
                    background = config.item("gui.background"),
                    rankCompleted = config.item("gui.rank-completed"),
                    rankCurrent = config.item("gui.rank-current"),
                    rankNext = config.item("gui.rank-next"),
                    path = config.item("gui.path"),
                    promotion = config.item("gui.promotion"),
                ),
            )
        }
    }
}

private fun ru.arc.config.Config.item(path: String): GuiItemSpec = GuiItemSpec(
    material = string("$path.material").trim().uppercase(),
    customModelData = int("$path.custom-model-data"),
)
