package tororo1066.magicitemtransporter.inventory

import kotlinx.coroutines.launch
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import tororo1066.magicitemtransporter.Database
import tororo1066.magicitemtransporter.MagicItemTransporter
import tororo1066.tororopluginapi.SJavaPlugin
import tororo1066.tororopluginapi.sInventory.SInventory
import tororo1066.tororopluginapi.sInventory.SInventoryItem
import tororo1066.tororopluginapi.utils.returnItem

class StoreInventory: SInventory(SJavaPlugin.plugin, "アイテムを保存する", 1) {

    val slots = (0 until 8).toList()
    override var savePlaceItems = true
    var save = false

    init {
        setOnClick { e ->
            e.isCancelled = true
            val item = e.currentItem ?: return@setOnClick
            val clickedInventory = e.clickedInventory ?: return@setOnClick
            val player = e.whoClicked as? Player ?: return@setOnClick
            if (clickedInventory !is PlayerInventory) {
                if (e.slot in slots) {
                    val clone = item.clone()
                    item.amount = 0
                    player.returnItem(clone)
                }
                return@setOnClick
            }

            if (!isValidItem(item)) return@setOnClick
            val clone = item.clone()
            for (i in slots) {
                if (getItem(i) == null) {
                    item.amount = 0
                    inv.setItem(i, clone)
                    return@setOnClick
                }
            }
        }

        setOnClose { e ->
            if (save) return@setOnClose
            val player = e.player as? Player ?: return@setOnClose
            val itemsToReturn = slots.mapNotNull { getItem(it) }
            player.returnItems(itemsToReturn)
        }
    }

    private fun isValidItem(item: ItemStack): Boolean {
        val wand = MagicItemTransporter.magicAPI.controller.getIfWand(item) ?: return false
        val template = wand.templateKey ?: return false
        return Database.allowedWandsCache.containsKey(template)
    }

    private fun Player.returnItems(items: List<ItemStack>) {
        for (item in items) {
            this.returnItem(item)
        }
    }

    override fun renderMenu(p: Player): Boolean {
        setItem(
            8,
            SInventoryItem(Material.LIME_STAINED_GLASS_PANE)
                .setDisplayName("§aアイテムを保存する")
                .setCanClick(false)
                .setClickEvent {
                    save = true
                    p.closeInventory()
                    val items = slots.mapNotNull { getItem(it) }

                    if (items.isEmpty()) {
                        p.sendMessage("${MagicItemTransporter.PREFIX}§c保存するアイテムがありません")
                        return@setClickEvent
                    }

                    val wandsToSave = items.mapNotNull { item -> MagicItemTransporter.magicAPI.controller.getIfWand(item) }
                    if (items.size != wandsToSave.size) {
                        p.sendMessage("${MagicItemTransporter.PREFIX}§c保存できないアイテムが含まれています")
                        p.returnItems(items)
                        return@setClickEvent
                    }
                    MagicItemTransporter.scope.launch {
                        val (result, message) = Database.store(p, wandsToSave)
                        if (result) {
                            p.sendMessage("${MagicItemTransporter.PREFIX}§aアイテムの保存に成功しました")
                        } else {
                            p.sendMessage("${MagicItemTransporter.PREFIX}§c${message ?: "不明なエラー"}")
                            p.returnItems(items)
                        }
                    }
                }
        )
        return true
    }
}