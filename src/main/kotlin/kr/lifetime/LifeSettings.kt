package kr.lifetime

import io.github.Shai0717.kolor.KolorSchema
import org.bukkit.configuration.ConfigurationSection

internal data class LifeSettings(val startingLife: Long, val killReward: Long, val actionCosts: Map<String, Long>) {
    companion object {
        private val actionDefaults = mapOf(
            "block-break" to 2L, "block-place" to 1L, "entity-attack" to 2L, "damage-taken" to 5L,
            "craft" to 1L, "consume" to 1L, "sprint-start" to 1L, "interact" to 0L
        )

        fun from(section: ConfigurationSection): LifeSettings {
            val schema = KolorSchema(section)
            val costs = actionDefaults.mapValues { (action, default) ->
                schema.long("action-costs.$action", default, 0L..Long.MAX_VALUE)
            }
            return LifeSettings(
                schema.long("starting-life-seconds", 3600, 1L..Long.MAX_VALUE),
                schema.long("mob-kill-reward-seconds", 30, 0L..Long.MAX_VALUE),
                costs
            )
        }
    }
}
