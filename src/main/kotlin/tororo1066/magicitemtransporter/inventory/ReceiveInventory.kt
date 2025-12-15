package tororo1066.magicitemtransporter.inventory

import kotlinx.coroutines.launch
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import tororo1066.magicitemtransporter.Database
import tororo1066.magicitemtransporter.MagicItemTransporter
import tororo1066.tororopluginapi.defaultMenus.LargeSInventory
import tororo1066.tororopluginapi.sInventory.SInventoryItem
import tororo1066.tororopluginapi.utils.returnItem
import java.util.concurrent.atomic.AtomicBoolean

class ReceiveInventory(val p: Player): LargeSInventory("アイテムを受け取る") {

    val receivableItems = mutableMapOf<String, ItemStack>()
    var receiving = AtomicBoolean(false)

    init {
        setOnClick { e ->
            e.isCancelled = true
        }

        MagicItemTransporter.scope.launch {
            val items = Database.getStoredItems(p)
            receivableItems.putAll(items)
            allRenderMenu(p)
        }
    }

    override fun renderMenu(): Boolean {
        val items = ArrayList<SInventoryItem>()
        receivableItems.forEach { (key, itemStack) ->
            items.add(
                SInventoryItem(itemStack.clone()).setClickEvent { _ ->
                    if (receiving.get()) return@setClickEvent
                    receiving.set(true)
                    MagicItemTransporter.scope.launch {
                        val (result, message) = Database.receive(p, key)
                        if (result != null) {
                            p.returnItem(result)
                            receivableItems.remove(key)
                            allRenderMenu(p)
                        } else {
                            p.sendMessage("${MagicItemTransporter.PREFIX}§c${message ?: "不明なエラー"}")
                        }
                        receiving.set(false)
                    }
                }
            )
        }

        setResourceItems(items)
        return true
    }
}