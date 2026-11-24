package kr.lifetime

import com.mojang.brigadier.arguments.LongArgumentType
import io.github.Shai0717.kolor.KolorCommand
import io.github.Shai0717.kolor.KolorConfig
import io.github.Shai0717.kolor.KolorPlugin
import io.github.Shai0717.kolor.KolorScheduler
import io.github.Shai0717.kolor.KolorTask
import io.github.Shai0717.kolor.kolorCommand
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.logging.Level
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.GameMode
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Monster
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.inventory.CraftItemEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerToggleSprintEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Objective
import org.bukkit.scoreboard.Scoreboard

class LifeTimePlugin : KolorPlugin(), Listener {
    private val remainingLife = mutableMapOf<UUID, Long>()
    private val bossBars = mutableMapOf<UUID, BossBar>()
    private val gameModesBeforeElimination = mutableMapOf<UUID, GameMode>()
    private val clocks = mutableMapOf<UUID, LifeClock>()
    private val fractionalElapsed = mutableMapOf<UUID, Long>()
    private val playerScoreboards = mutableMapOf<UUID, Scoreboard>()
    private val previousScoreboards = mutableMapOf<UUID, Scoreboard>()
    private val scoreboardEntries = mutableMapOf<UUID, MutableSet<String>>()
    private val scheduler by lazy { KolorScheduler(this) }
    private lateinit var lifetimesFile: File
    private lateinit var settings: LifeSettings
    private var dataDirty = false
    private var lifeTask: KolorTask? = null
    private var lastSaveNanos = 0L

    override fun onEnable() {
        settings = readSettings()
        lifetimesFile = File(dataFolder, "lifetimes.yml")
        loadLifetimes()
        server.pluginManager.registerEvents(this, this)
        registerCommands()
        Bukkit.getOnlinePlayers().forEach(::initializePlayer)
        lastSaveNanos = System.nanoTime()
        lifeTask = scheduler.runGlobal(delayTicks = 1, periodTicks = 1) { tickLife() }
    }

    override fun onKolorDisable() {
        lifeTask?.cancel()
        bossBars.values.forEach { it.removeAll() }
        bossBars.clear()
        Bukkit.getOnlinePlayers().forEach {
            advanceLife(it)
            restoreScoreboard(it)
        }
        if (::lifetimesFile.isInitialized) saveLifetimes()
    }

    private fun readSettings(): LifeSettings {
        val yaml = KolorConfig.load(File(dataFolder, "config.yml"), javaClass, "config.yml")
        return LifeSettings.from(yaml)
    }

    private fun loadLifetimes() {
        if (!lifetimesFile.exists()) return
        val data = YamlConfiguration()
        try {
            data.load(lifetimesFile)
        } catch (exception: IOException) {
            throw IllegalStateException("Cannot read $lifetimesFile", exception)
        } catch (exception: org.bukkit.configuration.InvalidConfigurationException) {
            throw IllegalStateException("Invalid lifetime data in $lifetimesFile", exception)
        }
        data.getConfigurationSection("players")?.let { players ->
            for (id in players.getKeys(false)) {
                try {
                    val uuid = UUID.fromString(id)
                    val life = players.getLong(id, -1)
                    require(life >= 0) { "Invalid lifetime for $id" }
                    remainingLife[uuid] = life
                } catch (exception: IllegalArgumentException) {
                    throw IllegalStateException("Invalid lifetime entry: $id", exception)
                }
            }
        }
        data.getConfigurationSection("elapsed-nanos")?.let { fractions ->
            for (id in fractions.getKeys(false)) {
                try {
                    val uuid = UUID.fromString(id)
                    val fraction = fractions.getLong(id, -1)
                    require(fraction in 0 until 1_000_000_000L)
                    fractionalElapsed[uuid] = fraction
                } catch (exception: IllegalArgumentException) {
                    throw IllegalStateException("Invalid elapsed-nanos entry: $id", exception)
                }
            }
        }
        data.getConfigurationSection("eliminated-game-modes")?.let { modes ->
            for (id in modes.getKeys(false)) {
                try {
                    val uuid = UUID.fromString(id)
                    val mode = requireNotNull(modes.getString(id))
                    gameModesBeforeElimination[uuid] = GameMode.valueOf(mode)
                } catch (exception: IllegalArgumentException) {
                    throw IllegalStateException("Invalid eliminated-game-modes entry: $id", exception)
                }
            }
        }
    }

    private fun saveLifetimes() {
        if (!dataDirty) return
        val data = YamlConfiguration()
        remainingLife.forEach { (id, value) -> data.set("players.$id", value) }
        fractionalElapsed.forEach { (id, value) -> data.set("elapsed-nanos.$id", value) }
        gameModesBeforeElimination.forEach { (id, mode) -> data.set("eliminated-game-modes.$id", mode.name) }
        try {
            data.save(lifetimesFile)
            dataDirty = false
            lastSaveNanos = System.nanoTime()
        } catch (exception: IOException) {
            logger.log(Level.SEVERE, "Could not save player lifetimes", exception)
        }
    }

    private fun initializePlayer(player: Player) {
        val id = player.uniqueId
        clocks[id] = LifeClock(System.nanoTime(), fractionalElapsed[id] ?: 0)
        if (id !in remainingLife) {
            remainingLife[id] = settings.startingLife
            dataDirty = true
            saveLifetimes()
        }
        if (life(id) <= 0) {
            if (player.gameMode == GameMode.SPECTATOR && id !in gameModesBeforeElimination) {
                gameModesBeforeElimination[id] = GameMode.SURVIVAL
                dataDirty = true
                logger.warning("No saved pre-elimination game mode for ${player.name}; using SURVIVAL.")
                saveLifetimes()
            }
            player.sendMessage("${ChatColor.RED}수명이 모두 소진되었습니다. 수명을 받을 때까지 탈락 상태입니다.")
            scheduler.runGlobal {
                if (player.isOnline && life(id) <= 0) eliminate(player)
            }
        }
        updateBossBar(player)
        setupScoreboard(player)
        updateScoreboard(player)
    }

    private fun tickLife() {
        var changed = false
        Bukkit.getOnlinePlayers().forEach { changed = advanceLife(it) || changed }
        if (changed) Bukkit.getOnlinePlayers().forEach(::updateBossBar)
        if (dataDirty && System.nanoTime() - lastSaveNanos >= 60_000_000_000L) saveLifetimes()
    }

    private fun advanceLife(player: Player): Boolean {
        val id = player.uniqueId
        val clock = clocks[id] ?: return false
        val elapsed = clock.advance(System.nanoTime())
        fractionalElapsed[id] = clock.fractionalNanos
        dataDirty = true
        val current = life(id)
        if (current <= 0 || elapsed == 0L) return false
        val updated = current - minOf(current, elapsed)
        remainingLife[id] = updated
        if (updated == 0L) expire(player)
        return true
    }

    private fun life(id: UUID): Long = remainingLife[id] ?: settings.startingLife

    private fun charge(player: Player, action: String) {
        advanceLife(player)
        val cost = settings.actionCosts.getValue(action)
        if (cost == 0L) return
        val id = player.uniqueId
        val current = life(id)
        if (current <= 0) {
            eliminate(player)
            return
        }
        val updated = current - minOf(current, cost)
        remainingLife[id] = updated
        dataDirty = true
        player.sendActionBar(Component.text("행동으로 수명 ${formatDuration(current - updated)} 소비 / 남은 수명 ${formatDuration(updated)}", NamedTextColor.GOLD))
        if (updated == 0L) expire(player)
        updateBossBar(player)
    }

    private fun eliminate(player: Player) {
        val id = player.uniqueId
        if (life(id) > 0) return
        val first = id !in gameModesBeforeElimination
        if (first) {
            gameModesBeforeElimination[id] = player.gameMode
            dataDirty = true
            saveLifetimes()
        }
        if (player.isDead) return
        if (first && player.gameMode != GameMode.SPECTATOR) player.health = 0.0
        else if (player.gameMode != GameMode.SPECTATOR) player.gameMode = GameMode.SPECTATOR
    }

    private fun expire(player: Player) {
        player.sendTitle("${ChatColor.DARK_RED}수명 종료", "${ChatColor.RED}수명이 다했습니다.", 0, 40, 10)
        player.sendMessage("${ChatColor.RED}수명이 다해 즉사했습니다.")
        eliminate(player)
    }

    private fun restoreAfterLifeGrant(player: Player) {
        val id = player.uniqueId
        gameModesBeforeElimination.remove(id)?.let {
            player.gameMode = it
            dataDirty = true
        }
        clocks[id]?.let {
            it.reset(System.nanoTime())
            fractionalElapsed[id] = 0
        }
    }

    private fun updateBossBar(player: Player) {
        val id = player.uniqueId
        val bar = bossBars.getOrPut(id) { Bukkit.createBossBar("", BarColor.GREEN, BarStyle.SOLID) }
        val remaining = life(id)
        val progress = (remaining.toDouble() / settings.startingLife).coerceIn(0.0, 1.0)
        bar.progress = progress
        bar.color = if (progress <= 0.2) BarColor.RED else if (progress <= 0.5) BarColor.YELLOW else BarColor.GREEN
        bar.setTitle("${ChatColor.GOLD}남은 수명: ${ChatColor.WHITE}${formatDuration(remaining)}")
        if (player !in bar.players) bar.addPlayer(player)
        updateScoreboard(player)
    }

    private fun setupScoreboard(player: Player) {
        val id = player.uniqueId
        if (id in playerScoreboards) return
        val manager = Bukkit.getScoreboardManager() ?: run {
            logger.warning("Could not create a lifetime scoreboard: scoreboard manager is unavailable.")
            return
        }
        val scoreboard = manager.newScoreboard
        scoreboard.registerNewObjective("lifetime", "dummy", "${ChatColor.GOLD}수명 경쟁").displaySlot = DisplaySlot.SIDEBAR
        previousScoreboards[id] = player.scoreboard
        playerScoreboards[id] = scoreboard
        scoreboardEntries[id] = mutableSetOf()
        player.scoreboard = scoreboard
    }

    private fun updateScoreboard(viewer: Player) {
        val id = viewer.uniqueId
        val scoreboard = playerScoreboards[id] ?: return
        val objective = scoreboard.getObjective("lifetime") ?: run {
            logger.warning("Lifetime scoreboard objective is missing for ${viewer.name}.")
            return
        }
        val entries = scoreboardEntries.getValue(id)
        entries.forEach(scoreboard::resetScores)
        entries.clear()
        val ranked = Bukkit.getOnlinePlayers().sortedWith(
            compareByDescending<Player> { life(it.uniqueId) }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        )
        val alive = ranked.count { life(it.uniqueId) > 0 }
        fun line(text: String, score: Int) {
            objective.getScore(text).score = score
            entries.add(text)
        }
        line("${ChatColor.GRAY}접속 ${ranked.size}  ${ChatColor.GREEN}생존 $alive", 14)
        line("${ChatColor.YELLOW}남은 수명 순위", 13)
        ranked.take(6).forEachIndexed { index, player ->
            val remaining = life(player.uniqueId)
            val color = if (remaining <= 0) ChatColor.DARK_GRAY else if (player.uniqueId == id) ChatColor.AQUA else ChatColor.WHITE
            val status = if (remaining <= 0) "${ChatColor.RED} 탈락" else "${ChatColor.GRAY} ${formatCompactDuration(remaining)}"
            line("${ChatColor.GOLD}#${index + 1} $color${player.name}$status", 12 - index)
        }
        line("${ChatColor.AQUA}내 순위: ${ChatColor.WHITE}#${ranked.indexOf(viewer) + 1}${ChatColor.GRAY} / ${ranked.size}", 6)
        line("${ChatColor.GOLD}내 수명", 5)
        val remaining = life(id)
        val filled = Math.round((remaining.toDouble() / settings.startingLife).coerceIn(0.0, 1.0) * 10).toInt()
        line("${ChatColor.GREEN}${"■".repeat(filled)}${ChatColor.DARK_GRAY}${"■".repeat(10 - filled)}", 4)
        line("${ChatColor.WHITE}남은 시간 ${ChatColor.YELLOW}${formatDuration(remaining)}", 3)
        line("${ChatColor.DARK_GRAY}시간과 행동이 수명을 소모", 2)
        line("${ChatColor.GRAY}/수명 으로 확인", 1)
    }

    private fun restoreScoreboard(player: Player) {
        val id = player.uniqueId
        val scoreboard = playerScoreboards.remove(id)
        val previous = previousScoreboards.remove(id)
        scoreboardEntries.remove(id)
        if (scoreboard != null && previous != null && player.scoreboard === scoreboard) player.scoreboard = previous
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) = charge(event.player, "block-break")

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) = charge(event.player, "block-place")

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityAttack(event: EntityDamageByEntityEvent) {
        val attacker = when (val damager = event.damager) {
            is Player -> damager
            is Projectile -> damager.shooter as? Player
            else -> null
        }
        if (attacker != null) charge(attacker, "entity-attack")
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onEntityDeath(event: EntityDeathEvent) {
        if (event.entity !is Monster) return
        val killer = event.entity.killer ?: return
        if (!killer.isOnline) return
        advanceLife(killer)
        val current = life(killer.uniqueId)
        if (settings.killReward <= 0 || current <= 0 || killer.isDead) return
        val earned = minOf(settings.killReward, Long.MAX_VALUE - current)
        if (earned == 0L) return
        remainingLife[killer.uniqueId] = current + earned
        dataDirty = true
        killer.sendActionBar(Component.text("적 처치: 수명 +${formatDuration(earned)}", NamedTextColor.GREEN))
        updateBossBar(killer)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDamageTaken(event: EntityDamageEvent) {
        if (event.finalDamage > 0) (event.entity as? Player)?.let { charge(it, "damage-taken") }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCraft(event: CraftItemEvent) {
        (event.whoClicked as? Player)?.let { charge(it, "craft") }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onConsume(event: PlayerItemConsumeEvent) = charge(event.player, "consume")

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSprint(event: PlayerToggleSprintEvent) {
        if (event.isSprinting) charge(event.player, "sprint-start")
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand == EquipmentSlot.HAND && event.action in setOf(Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK)) {
            charge(event.player, "interact")
        }
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) = initializePlayer(event.player)

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val player = event.player
        advanceLife(player)
        clocks.remove(player.uniqueId)
        bossBars.remove(player.uniqueId)?.removePlayer(player)
        restoreScoreboard(player)
        saveLifetimes()
    }

    @EventHandler
    fun onRespawn(event: PlayerRespawnEvent) {
        val player = event.player
        if (life(player.uniqueId) <= 0) {
            scheduler.runGlobal {
                if (player.isOnline && life(player.uniqueId) <= 0) {
                    player.gameMode = GameMode.SPECTATOR
                    player.sendMessage("${ChatColor.RED}수명이 소진되어 탈락했습니다. 수명을 받으면 복귀할 수 있습니다.")
                }
            }
        }
    }

    @EventHandler
    fun onWorldChange(event: PlayerChangedWorldEvent) = updateBossBar(event.player)

    private fun registerCommands() {
        kolorCommand("life", "수명") {
            executes { context ->
                val player = context.sender as? Player
                if (player == null) context.sender.sendMessage("콘솔에서는 /life <give|take|set|reload> 명령을 사용하세요.")
                else {
                    advanceLife(player)
                    player.sendMessage("${ChatColor.GOLD}남은 수명: ${ChatColor.WHITE}${formatDuration(life(player.uniqueId))}")
                }
                1
            }
            for (name in listOf("pay", "전달")) {
                literal(name) {
                    playerAndAmount { sender, target, amount -> transferLife(sender, target, amount) }
                }
            }
            for (name in listOf("give", "take", "set")) {
                literal(name) {
                    requires { it.hasPermission("lifetime.admin") }
                    playerAndAmount { sender, target, amount -> manageLife(sender, target, amount, name) }
                }
            }
            literal("reload") {
                requires { it.hasPermission("lifetime.admin") }
                executes { context ->
                    try {
                        settings = readSettings()
                        Bukkit.getOnlinePlayers().forEach(::updateBossBar)
                        context.sender.sendMessage("${ChatColor.GREEN}LifeTime 설정을 다시 불러왔습니다.")
                        1
                    } catch (exception: IllegalArgumentException) {
                        logger.log(Level.WARNING, "Could not reload LifeTime configuration", exception)
                        context.sender.sendMessage("${ChatColor.RED}설정이 올바르지 않아 기존 설정을 유지합니다: ${exception.message}")
                        0
                    } catch (exception: IllegalStateException) {
                        logger.log(Level.WARNING, "Could not reload LifeTime configuration", exception)
                        context.sender.sendMessage("${ChatColor.RED}설정을 읽지 못해 기존 설정을 유지합니다: ${exception.message}")
                        0
                    }
                }
            }
        }
    }

    private fun KolorCommand.playerAndAmount(action: (CommandSender, String, Long) -> Unit) {
        stringArg("player") {
            suggests { _, builder -> Bukkit.getOnlinePlayers().forEach { builder.suggest(it.name) } }
            argument("seconds", LongArgumentType.longArg(0), Long::class.javaObjectType,
                { _, value -> value }) {
                executes { context ->
                    action(context.sender, context.string("player"), context["seconds"])
                    1
                }
            }
        }
    }

    private fun manageLife(sender: CommandSender, targetName: String, amount: Long, operation: String) {
        val target = Bukkit.getPlayerExact(targetName) ?: run {
            sender.sendMessage("${ChatColor.RED}접속 중인 플레이어만 지정할 수 있습니다.")
            return
        }
        if (amount == 0L && operation != "set") {
            sender.sendMessage("${ChatColor.RED}give/take에는 1 이상을 입력해야 합니다.")
            return
        }
        advanceLife(target)
        val id = target.uniqueId
        val current = life(id)
        val updated = when (operation) {
            "give" -> current + minOf(amount, Long.MAX_VALUE - current)
            "take" -> current - minOf(current, amount)
            else -> amount
        }
        remainingLife[id] = updated
        dataDirty = true
        if (updated > 0 && current == 0L) restoreAfterLifeGrant(target)
        else if (updated == 0L) eliminate(target)
        updateBossBar(target)
        saveLifetimes()
        sender.sendMessage("${ChatColor.GREEN}${target.name}의 남은 수명을 ${formatDuration(updated)}으로 설정했습니다.")
        if (sender != target) target.sendMessage("${ChatColor.GOLD}관리자가 남은 수명을 ${formatDuration(updated)}으로 변경했습니다.")
    }

    private fun transferLife(sender: CommandSender, targetName: String, amount: Long) {
        val player = sender as? Player ?: run {
            sender.sendMessage("${ChatColor.RED}플레이어만 수명을 전달할 수 있습니다.")
            return
        }
        val target = Bukkit.getPlayerExact(targetName)
        if (target == null || target.uniqueId == player.uniqueId) {
            sender.sendMessage("${ChatColor.RED}접속 중인 다른 플레이어를 지정하세요.")
            return
        }
        if (amount <= 0) {
            sender.sendMessage("${ChatColor.RED}초는 1 이상이어야 합니다.")
            return
        }
        advanceLife(player)
        advanceLife(target)
        val ownLife = life(player.uniqueId)
        val targetLife = life(target.uniqueId)
        if (player.isDead) {
            sender.sendMessage("${ChatColor.RED}전달 후 수명이 최소 1초 이상 남아야 합니다.")
            return
        }
        val transfer = when (val result = calculateTransfer(ownLife, targetLife, amount)) {
            LifeTransfer.InsufficientLife -> {
                sender.sendMessage("${ChatColor.RED}전달 후 수명이 최소 1초 이상 남아야 합니다.")
                return
            }
            LifeTransfer.RecipientOverflow -> {
                sender.sendMessage("${ChatColor.RED}상대방의 수명이 최대치를 넘습니다.")
                return
            }
            is LifeTransfer.Success -> result
        }
        remainingLife[player.uniqueId] = transfer.sender
        remainingLife[target.uniqueId] = transfer.recipient
        dataDirty = true
        if (targetLife == 0L) restoreAfterLifeGrant(target)
        updateBossBar(player)
        updateBossBar(target)
        saveLifetimes()
        sender.sendMessage("${ChatColor.GREEN}${target.name}에게 수명 ${formatDuration(amount)}을 전달했습니다.")
        target.sendMessage("${ChatColor.GREEN}${player.name}에게 수명 ${formatDuration(amount)}을 받았습니다.")
    }

    companion object {
        private fun formatDuration(seconds: Long): String =
            String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds % 3600 / 60, seconds % 60)

        private fun formatCompactDuration(seconds: Long): String =
            String.format(Locale.ROOT, "%02d:%02d", seconds / 3600, seconds / 60 % 60)
    }
}
