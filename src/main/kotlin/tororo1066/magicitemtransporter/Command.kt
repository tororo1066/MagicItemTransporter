package tororo1066.magicitemtransporter

import kotlinx.coroutines.launch
import tororo1066.commandapi.argumentType.StringArg
import tororo1066.magicitemtransporter.inventory.ReceiveInventory
import tororo1066.magicitemtransporter.inventory.StoreInventory
import tororo1066.tororopluginapi.annotation.SCommandV2Body
import tororo1066.tororopluginapi.sCommand.v2.SCommandV2

@Suppress("UNUSED")
class Command: SCommandV2("mit") {

    init {
        root.setPermission("magicitemtransporter.user")
    }

    @SCommandV2Body
    val transport = command {
        literal("store") {
            setPlayerFunctionExecutor { sender, _, _ ->
                StoreInventory().open(sender)
            }
        }

        literal("receive") {
            setPlayerFunctionExecutor { sender, _, _ ->
                ReceiveInventory(sender).open(sender)
            }
        }
    }

    @SCommandV2Body
    val admin = command {
        setPermission("magicitemtransporter.op")
        literal("addAllowedWand") {
            argument("wand_key", StringArg.greedyPhrase()) {
                suggest { _ ->
                    MagicItemTransporter.magicAPI.wandKeys.map { it toolTip null }
                }
                setFunctionExecutor { sender, _, args ->
                    val wandKey = args.getArgument("wand_key", String::class.java)
                    val wand = MagicItemTransporter.magicAPI.controller.createWand(wandKey)
                    if (wand == null) {
                        sender.sendMessage("${MagicItemTransporter.PREFIX}§c杖が存在しません")
                        return@setFunctionExecutor
                    }
                    MagicItemTransporter.scope.launch {
                        val result = Database.addAllowedWand(wand)
                        if (result) {
                            sender.sendMessage("${MagicItemTransporter.PREFIX}§a追加しました")
                        } else {
                            sender.sendMessage("${MagicItemTransporter.PREFIX}§c追加に失敗しました")
                        }
                    }
                }
            }
        }
    }
}