package kr.lifetime

import org.bukkit.configuration.file.YamlConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LifeSettingsTest {
    @Test
    fun `Kolor schema reads old config and supplies missing defaults`() {
        val yaml = YamlConfiguration()
        yaml.loadFromString("starting-life-seconds: 600\naction-costs:\n  block-break: 8\n")
        val settings = LifeSettings.from(yaml)
        assertEquals(600, settings.startingLife)
        assertEquals(30, settings.killReward)
        assertEquals(8, settings.actionCosts["block-break"])
        assertEquals(1, settings.actionCosts["block-place"])
    }

    @Test
    fun `Kolor schema rejects invalid game rules`() {
        val yaml = YamlConfiguration()
        yaml.set("starting-life-seconds", 0)
        assertFailsWith<IllegalArgumentException> { LifeSettings.from(yaml) }
        yaml.set("starting-life-seconds", 600)
        yaml.set("action-costs.craft", -1)
        assertFailsWith<IllegalArgumentException> { LifeSettings.from(yaml) }
        yaml.set("action-costs.craft", "free")
        assertFailsWith<IllegalArgumentException> { LifeSettings.from(yaml) }
    }
}
