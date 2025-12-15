package tororo1066.magicitemtransporter

import com.elmakers.mine.bukkit.api.magic.MagicAPI
import com.github.shynixn.mccoroutine.bukkit.minecraftDispatcher
import com.google.common.io.ByteStreams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.bukkit.Bukkit
import org.bukkit.event.player.PlayerJoinEvent
import tororo1066.tororopluginapi.SJavaPlugin
import tororo1066.tororopluginapi.opentelemetry.SOpenTelemetry
import tororo1066.tororopluginapi.sEvent.SEvent

class MagicItemTransporter: SJavaPlugin() {

    companion object {
        const val PREFIX = "§6[§dMagicItemTransporter§6]§r"
        var serverName: String? = null
        lateinit var magicAPI: MagicAPI
        lateinit var openTelemetry: SOpenTelemetry

        val scope by lazy {
            CoroutineScope(SupervisorJob() + plugin.minecraftDispatcher)
        }
    }

    override fun onStart() {

        saveDefaultConfig()

        if (!server.serverConfig.isProxyEnabled) {
            logger.severe("BungeeCord or Velocity must be enabled in server config to use this plugin.")
            server.pluginManager.disablePlugin(this)
            return
        }

        val magicPlugin = server.pluginManager.getPlugin("Magic")
        if (magicPlugin == null || magicPlugin !is MagicAPI) {
            logger.severe("Magic plugin not found or invalid. Disabling MagicItemTransporter.")
            server.pluginManager.disablePlugin(this)
            return
        }
        magicAPI = magicPlugin

        openTelemetry = SOpenTelemetry(this)

        server.messenger.registerIncomingPluginChannel(this, "BungeeCord") { _, _, message ->
            val data = ByteStreams.newDataInput(message)
            val subChannel = data.readUTF()
            when (subChannel) {
                "GetServer" -> {
                    serverName = data.readUTF()
                }
            }
        }
        server.messenger.registerOutgoingPluginChannel(this, "BungeeCord")
        SEvent().register<PlayerJoinEvent> { e ->
            Bukkit.getScheduler().runTaskLater(
                this,
                Runnable {
                    ByteStreams.newDataOutput().apply {
                        writeUTF("GetServer")
                    }.toByteArray().let {
                        e.player.sendPluginMessage(this, "BungeeCord", it)
                    }
                },
                20
            )
        }

        Database.reload()
        Command()
    }

    override fun onEnd() {
        Database.close()
    }
}