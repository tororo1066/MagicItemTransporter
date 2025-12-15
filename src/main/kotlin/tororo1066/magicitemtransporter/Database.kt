package tororo1066.magicitemtransporter

import com.elmakers.mine.bukkit.api.wand.Wand
import com.github.shynixn.mccoroutine.bukkit.asyncDispatcher
import com.google.gson.Gson
import com.mongodb.client.model.Filters
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.codec.StringCodec
import io.lettuce.core.pubsub.RedisPubSubListener
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import io.opentelemetry.api.logs.Severity
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bson.Document
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import tororo1066.magicitemtransporter.data.AllowedWand
import tororo1066.magicitemtransporter.data.Result
import tororo1066.magicitemtransporter.enumClass.Status
import tororo1066.tororopluginapi.SJavaPlugin
import tororo1066.tororopluginapi.database.SSession
import tororo1066.tororopluginapi.database.mongo.SMongo
import tororo1066.tororopluginapi.database.redis.SRedis
import tororo1066.tororopluginapi.sItem.SItem
import java.util.Date
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

object Database {
    var database: SMongo? = null
    var redis: SRedis? = null
    var pubSubConnection: StatefulRedisPubSubConnection<String, String>? = null
    var cmdConnection: StatefulRedisConnection<String, String>? = null

    val gson = Gson()

    var tablePrefix = "mit_"

    val itemDataTable: String
        get() = tablePrefix + "item_data"
    val allowedWandsTable: String
        get() = tablePrefix + "allowed_wands"

    private const val CHANNEL_ID = "magic_item_transporter"
    private const val UPDATE_MESSAGE = "update"

    val allowedWandsCache = ConcurrentHashMap<String, AllowedWand>()

    fun close() {
        database?.close()
        pubSubConnection?.close()
        cmdConnection?.close()
        redis?.client?.shutdown()
    }

    fun reload() {

        close()

        database = SMongo(SJavaPlugin.plugin, configFile = null, configPath = "mongodb")
        redis = SRedis(SJavaPlugin.plugin)

        tablePrefix = SJavaPlugin.plugin.config.getString("mongodb.tablePrefix", "mit_")!!

        val database = database ?: return
        val tasks = mutableListOf<CompletableFuture<Boolean>>()

        tasks.add(
            database.asyncCreateTable(
                itemDataTable,
                mapOf()
            )
        )

        /*
        uuid: string
        player: string
        wands: [
            {
                wand_key: string,
                status: string,
                data: [
                    {
                        item: base64,
                        server: string,
                        timestamp: date
                    }
                ]
            },
            ...
        ]
         */

        tasks.add(
            database.asyncCreateTable(
                allowedWandsTable,
                mapOf()
            )
        )

        CompletableFuture.allOf(*tasks.toTypedArray()).whenComplete { _, _ ->
            MagicItemTransporter.scope.launch {
                val wands = getAllowedWands()
                allowedWandsCache.clear()
                wands.forEach { wand ->
                    allowedWandsCache[wand.wandKey] = wand
                }
            }
        }

        startRedisListener()
    }

    private fun startRedisListener() {
        val client = redis?.client ?: return
        pubSubConnection = client.connectPubSub(StringCodec())
        cmdConnection = client.connect()
        val pubSub = pubSubConnection ?: return
        val listener = object : RedisPubSubListener<String, String> {
            override fun message(channel: String, message: String) {
                if (channel == CHANNEL_ID && message == UPDATE_MESSAGE) {
                    MagicItemTransporter.scope.launch {
                        val wands = getAllowedWands()
                        allowedWandsCache.clear()
                        wands.forEach { wand ->
                            allowedWandsCache[wand.wandKey] = wand
                        }
                    }
                }
            }

            override fun message(pattern: String, channel: String, message: String) {}
            override fun subscribed(channel: String, count: Long) {}
            override fun psubscribed(pattern: String, count: Long) {}
            override fun unsubscribed(channel: String, count: Long) {}
            override fun punsubscribed(pattern: String, count: Long) {}
        }

        pubSub.addListener(listener)
        pubSub.sync().subscribe(CHANNEL_ID)
    }

    private fun getBase64FromWand(wand: Wand): String? {
        val key = wand.templateKey ?: return null
        val templateWand = MagicItemTransporter.magicAPI.controller.createWand(key) ?: return null
        val itemStack = templateWand.item ?: return null
        return SItem(itemStack).toByteArrayBase64()
    }

    private fun getItemStackFromWandKey(wandKey: String): ItemStack? {
        val templateWand = MagicItemTransporter.magicAPI.controller.createWand(wandKey) ?: return null
        return templateWand.item
    }

    suspend fun getAllowedWands(session: SSession? = null): List<AllowedWand> {
        val sDatabase = database ?: return emptyList()
        return withContext(SJavaPlugin.plugin.asyncDispatcher) {
            try {
                val results = if (session != null) {
                    sDatabase.select(allowedWandsTable, session = session)
                } else {
                    sDatabase.select(allowedWandsTable)
                }
                return@withContext results.mapNotNull { result ->
                    val wandKey = result.getString("wand_key")
                    val itemBase64 = result.getString("display_item")
                    val itemStack = SItem.byBase64(itemBase64)?.build() ?: return@mapNotNull null
                    AllowedWand(wandKey, itemStack)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                MagicItemTransporter.openTelemetry.logger.logRecordBuilder()
                    .setBody("Error getting allowed wands: ${e.message}")
                    .setSeverity(Severity.ERROR)
                    .emit()
                return@withContext emptyList()
            }
        }
    }

    suspend fun addAllowedWand(wand: Wand): Boolean {
        val sDatabase = database ?: return false
        val itemStack = wand.item ?: return false
        val itemBase64 = SItem(itemStack).toByteArrayBase64()
        return withContext(SJavaPlugin.plugin.asyncDispatcher) {
            try {
                val database = sDatabase.open()
                val collection = database.getCollection(allowedWandsTable)
                val result = collection.updateOne(
                    Filters.eq("wand_key", wand.templateKey),
                    Updates.setOnInsert(
                        "display_item",
                        itemBase64
                    ),
                    UpdateOptions().upsert(true)
                )

                if (result.upsertedId != null) {
                    MagicItemTransporter.openTelemetry.logger.logRecordBuilder()
                        .setBody("Added allowed wand ${wand.templateKey}")
                        .emit()
                } else {
                    MagicItemTransporter.openTelemetry.logger.logRecordBuilder()
                        .setBody("Allowed wand ${wand.templateKey} already exists")
                        .emit()
                }

                cmdConnection?.sync()?.publish(CHANNEL_ID, UPDATE_MESSAGE)
                return@withContext true

            } catch (e: Exception) {
                e.printStackTrace()
                MagicItemTransporter.openTelemetry.logger.logRecordBuilder()
                    .setBody("Error adding allowed wand ${wand.templateKey}: ${e.message}")
                    .setSeverity(Severity.ERROR)
                    .emit()
                return@withContext false
            }
        }
    }

    suspend fun store(player: Player, wands: List<Wand>): Result<Boolean> {
        val serverName = MagicItemTransporter.serverName ?: return Result(false, "サーバーを特定できませんでした")
        val sDatabase = database ?: return Result(false, "接続に失敗しました")
        val timestamp = Date()
        val result: Result<Boolean>
        sDatabase.transaction { session ->
            result = withContext(SJavaPlugin.plugin.asyncDispatcher) {
                try {
                    val allowedWands = getAllowedWands(session).map { it.wandKey }.toSet()
                    if (wands.any { it.templateKey !in allowedWands }) {
                        session.rollback()
                        return@withContext Result(false, "許可されていないアイテムが含まれています")
                    }

                    for (wand in wands) {
                        val key = wand.templateKey
                        val itemBase64 = getBase64FromWand(wand)
                        if (key == null || itemBase64 == null) {
                            session.rollback()
                            return@withContext Result(false, "アイテムの情報を取得できませんでした")
                        }

                        val database = sDatabase.open()
                        val collection = database.getCollection(itemDataTable)

                        val existingDocument = collection.find(
                            session.getMongoSession(),
                            Filters.eq("uuid", player.uniqueId.toString())
                        ).first()

                        val entryDocument = Document(
                            mapOf(
                                "item" to itemBase64,
                                "server" to serverName,
                                "timestamp" to timestamp
                            )
                        )

                        if (existingDocument != null) {
                            val wandsList = existingDocument.getList("wands", Document::class.java)
                            val wandDocument = wandsList.firstOrNull { doc ->
                                doc.getString("wand_key") == key
                            }

                            if (wandDocument != null) { // wand_keyが存在する場合は更新
                                val status = wandDocument.getString("status")
                                // 既に保存されている場合は終了
                                if (status == Status.STORED.name) {
                                    session.rollback()
                                    return@withContext Result(false, "既に保存されているアイテムが含まれています")
                                }

                                val dataList = wandDocument.getList("data", Document::class.java)
                                var overwritten = false
                                for (entry in dataList) {
                                    // 同じサーバーのデータがあれば上書き
                                    if (entry.getString("server") == serverName) {
                                        entry["item"] = itemBase64
                                        entry["timestamp"] = timestamp
                                        overwritten = true
                                        break
                                    }
                                }
                                if (!overwritten) {
                                    // なければ追加
                                    dataList.add(entryDocument)
                                }

                                val updateResult = collection.updateOne(
                                    session.getMongoSession(),
                                    Filters.eq("uuid", player.uniqueId.toString()),
                                    Updates.combine(
                                        Updates.set("wands.$[wand].data", dataList),
                                        Updates.set("wands.$[wand].status", Status.STORED.name)
                                    ),
                                    UpdateOptions().arrayFilters(
                                        listOf(
                                            Filters.eq("wand.wand_key", key)
                                        )
                                    )
                                )
                                if (updateResult.matchedCount == 0L || updateResult.modifiedCount == 0L) {
                                    session.rollback()
                                    return@withContext Result(false, "データの保存に失敗しました")
                                }
                            } else {
                                // wand_keyが存在しない場合は新規追加
                                val newWandDocument = Document(
                                    mapOf(
                                        "wand_key" to key,
                                        "status" to Status.STORED.name,
                                        "data" to listOf(entryDocument)
                                    )
                                )
                                val pushResult = collection.updateOne(
                                    session.getMongoSession(),
                                    Filters.eq("uuid", player.uniqueId.toString()),
                                    Updates.push("wands", newWandDocument)
                                )
                                if (pushResult.matchedCount == 0L || pushResult.modifiedCount == 0L) {
                                    session.rollback()
                                    return@withContext Result(false, "データの保存に失敗しました")
                                }
                            }

                        } else { // プレイヤーのドキュメントが存在しない場合は新規作成
                            val newPlayerDocument = Document(
                                mapOf(
                                    "uuid" to player.uniqueId.toString(),
                                    "name" to player.name,
                                    "wands" to listOf(
                                        Document(
                                            mapOf(
                                                "wand_key" to key,
                                                "status" to Status.STORED.name,
                                                "data" to listOf(entryDocument)
                                            )
                                        )
                                    )
                                )
                            )
                            val insertResult = collection.insertOne(
                                session.getMongoSession(),
                                newPlayerDocument
                            )
                            if (!insertResult.wasAcknowledged()) {
                                session.rollback()
                                return@withContext Result(false, "データの保存に失敗しました")
                            }
                        }

                        val log = mapOf(
                            "action" to Status.STORED.logName,
                            "uuid" to player.uniqueId.toString(),
                            "player" to player.name,
                            "wand_key" to key,
                            "item" to itemBase64,
                            "server" to serverName
                        )

                        val jsonLog = gson.toJson(log)

                        MagicItemTransporter.openTelemetry.logger.logRecordBuilder()
                            .setBody(jsonLog)
                            .emit()
                    }

                    session.commit()
                } catch (e: Exception) {
                    e.printStackTrace()
                    session.rollback()
                    MagicItemTransporter.openTelemetry.logger.logRecordBuilder()
                        .setBody("Error storing items for ${player.uniqueId}: ${e.message}")
                        .setSeverity(Severity.ERROR)
                        .emit()
                    return@withContext Result(false, "データの保存中にエラーが発生しました")
                }

                return@withContext Result(true, null)
            }
        }

        if (result.value) {
            MagicItemTransporter.openTelemetry.logger.logRecordBuilder()
                .setBody("Successfully stored ${wands.size} items for ${player.uniqueId}")
                .emit()
        }

        return result
    }

    suspend fun receive(player: Player, wandKey: String): Result<ItemStack?> {
        val serverName = MagicItemTransporter.serverName ?: return Result(null, "サーバーを特定できませんでした")
        val sDatabase = database ?: return Result(null, "接続に失敗しました")
        sDatabase.transaction { session ->
            fun rollbackAndReturn(message: String): Result<ItemStack?> {
                session.rollback()
                return Result(null, message)
            }

            return withContext(SJavaPlugin.plugin.asyncDispatcher) {
                try {
                    val database = sDatabase.open()
                    val collection = database.getCollection(itemDataTable)

                    val document = collection.find(
                        session.getMongoSession(),
                        Filters.eq("uuid", player.uniqueId.toString())
                    ).first() ?: return@withContext rollbackAndReturn("保存されたデータが見つかりませんでした")

                    val wandsList = document.getList("wands", Document::class.java)
                    val wandDocument = wandsList.firstOrNull { doc ->
                        doc.getString("wand_key") == wandKey
                    } ?: return@withContext rollbackAndReturn("指定されたアイテムが見つかりませんでした")

                    val status = wandDocument.getString("status")
                    if (status != Status.STORED.name) {
                        return@withContext rollbackAndReturn("このアイテムは保存されていません")
                    }
                    val dataList = wandDocument.getList("data", Document::class.java)
                    val itemEntry = dataList.firstOrNull { entry ->
                        entry.getString("server") == serverName
                    }

                    val itemStack = if (itemEntry != null) {
                        val itemBase64 = itemEntry.getString("item")
                        SItem.byBase64(itemBase64)?.build() ?: return@withContext rollbackAndReturn("アイテムのデータが破損しています")
                    } else {
                        getItemStackFromWandKey(wandKey) ?: return@withContext rollbackAndReturn("アイテムのデータが取得できませんでした")
                    }

                    val updateResult = collection.updateOne(
                        session.getMongoSession(),
                        Filters.eq("uuid", player.uniqueId.toString()),
                        Updates.set("wands.$[wand].status", Status.RECEIVED.name),
                        UpdateOptions().arrayFilters(
                            listOf(
                                Filters.eq("wand.wand_key", wandKey)
                            )
                        )
                    )
                    if (updateResult.matchedCount == 0L || updateResult.modifiedCount == 0L) {
                        return@withContext rollbackAndReturn("データの更新に失敗しました")
                    }

                    val log = mapOf(
                        "action" to Status.RECEIVED.logName,
                        "uuid" to player.uniqueId.toString(),
                        "player" to player.name,
                        "wand_key" to wandKey,
                        "item" to SItem(itemStack).toByteArrayBase64(),
                        "server" to serverName
                    )

                    val jsonLog = gson.toJson(log)

                    MagicItemTransporter.openTelemetry.logger.logRecordBuilder()
                        .setBody(jsonLog)
                        .emit()

                    session.commit()

                    return@withContext Result(itemStack, null)
                } catch (e: Exception) {
                    e.printStackTrace()
                    session.rollback()
                    MagicItemTransporter.openTelemetry.logger.logRecordBuilder()
                        .setBody("Error receiving item for ${player.uniqueId}: ${e.message}")
                        .setSeverity(Severity.ERROR)
                        .emit()
                    return@withContext Result(null, "データの取得中にエラーが発生しました")
                }
            }
        }
    }

    suspend fun getStoredItems(player: Player): Map<String, ItemStack> {
        val sDatabase = database ?: return emptyMap()
        val serverName = MagicItemTransporter.serverName ?: return emptyMap()
        return withContext(SJavaPlugin.plugin.asyncDispatcher) {
            try {
                val database = sDatabase.open()
                val collection = database.getCollection(itemDataTable)
                val document = collection.find(
                    Filters.eq("uuid", player.uniqueId.toString())
                ).first() ?: return@withContext emptyMap()

                val wandsList = document.getList("wands", Document::class.java)
                val result = mutableMapOf<String, ItemStack>()
                for (wandDoc in wandsList) {
                    val wandKey = wandDoc.getString("wand_key")
                    val status = wandDoc.getString("status")
                    if (status != Status.STORED.name) continue
                    val dataList = wandDoc.getList("data", Document::class.java)
                    val itemEntry = dataList.firstOrNull { entry ->
                        entry.getString("server") == serverName
                    }
                    val itemStack = if (itemEntry != null) {
                        val itemBase64 = itemEntry.getString("item")
                        SItem.byBase64(itemBase64)?.build() ?: continue
                    } else {
                        getItemStackFromWandKey(wandKey) ?: continue
                    }
                    result[wandKey] = itemStack
                }
                return@withContext result
            } catch (e: Exception) {
                e.printStackTrace()
                MagicItemTransporter.openTelemetry.logger.logRecordBuilder()
                    .setBody("Error getting stored items for ${player.uniqueId}: ${e.message}")
                    .setSeverity(Severity.ERROR)
                    .emit()
                return@withContext emptyMap()
            }
        }
    }
}