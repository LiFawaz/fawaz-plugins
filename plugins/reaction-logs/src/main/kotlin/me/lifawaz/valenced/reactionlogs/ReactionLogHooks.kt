package me.lifawaz.valenced.reactionlogs

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextSwitcher
import android.widget.TextView
import app.valenced.bridge.ValencedBridge
import app.valenced.xposed.api.DexFinder
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.text.Normalizer
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.WeakHashMap

private const val SHORTCUTS_FLEXBOX = "com.discord.reactions.ShortcutsFlexbox"
private const val MANAGE_REACTIONS_WIDGET =
    "com.discord.widgets.chat.managereactions.WidgetManageReactions"
private const val REACTION_USER_ITEM =
    "com.discord.widgets.chat.managereactions.ManageReactionsResultsAdapter\$ReactionUserItem"
private const val MG_RECYCLER_VIEW_HOLDER =
    "com.discord.utilities.mg_recycler.MGRecyclerViewHolder"
private const val STORE_STREAM = "com.discord.stores.StoreStream"
private const val STORE_USER = "com.discord.stores.StoreUser"

private val REMOVED_RED = Color.rgb(237, 66, 69)
private val REMOVED_TINT = Color.argb(48, 237, 66, 69)

internal class ReactionLogHooks(
    dexFinder: DexFinder,
    @Suppress("UNUSED_PARAMETER") bridge: ValencedBridge,
) {
    private data class Pill(
        val emoji: Any?,
        val burst: Boolean,
        val count: Int,
    )
    private data class OriginalPillStyle(
        val backgroundTint: ColorStateList?,
        val countColors: ColorStateList?,
    )
    private data class RemovalEntry(
        val id: String,
        val guildId: String?,
        val channelId: String,
        val messageId: String,
        val userId: String,
        val emojiKey: String,
        val reactionType: Int,
        val timestamp: Long,
    )
    private data class ActiveEntry(
        val identityKey: String,
        val guildId: String?,
        val channelId: String,
        val messageId: String,
        val userId: String,
        val emojiKey: String,
        val reactionType: Int,
    )
    private data class PopupContext(
        val guildId: String?,
        val channelId: String,
        val messageId: String,
        val emojiKey: String,
    )
    private data class RowBinding(
        val rowKey: String,
        val userId: String,
    )

    private val removedUsersByPillKey = LinkedHashMap<String, Set<String>>()
    private val removalHistory = LinkedHashMap<String, RemovalEntry>()
    private val activeReactions = LinkedHashMap<String, ActiveEntry>()
    private val popupRemovedRowKeys = LinkedHashSet<String>()

    private val retainedPillViews = LinkedHashMap<String, LinearLayout>()
    private val boundPills = WeakHashMap<LinearLayout, String>()
    private val originalPillStyles = WeakHashMap<LinearLayout, OriginalPillStyle>()

    private val userRows = WeakHashMap<View, RowBinding>()
    private val originalUserTextColors = WeakHashMap<TextView, ColorStateList>()
    private val originalUserRowTints = WeakHashMap<View, ColorStateList?>()
    private val originalAvatarForegrounds = WeakHashMap<View, Drawable?>()

    @Volatile private var popupContext: PopupContext? = null
    @Volatile private var sessionStartedAt: Long = 0L
    @Volatile private var lastPopupWidget: WeakReference<Any>? = null
    @Volatile private var lastPopupModel: Any? = null
    @Volatile private var configureUiMethod: Method? = null

    private val bindingMessageId = ThreadLocal<String?>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val unhooks = mutableListOf<XC_MethodHook.Unhook>()
    private val requestedUserFetches = LinkedHashSet<Long>()

    init {
        hookReactionPills(dexFinder)
        runCatching { hookReactionPopup(dexFinder) }.onFailure {
            XposedBridge.log("Reaction Logs: popup integration unavailable: \$it")
        }
    }

    /**
     * Receives a versioned snapshot from the JS Flux event observer. History and
     * active state are parsed separately; active reactors always come from Discord.
     */
    fun setRemoved(json: String) {
        val decoded = runCatching { JSONTokener(json).nextValue() }.getOrNull()
        val root = decoded as? JSONObject
        val legacyArray = decoded as? JSONArray
        val markerArray = root?.optJSONArray("activeRemovals") ?: legacyArray
        val markers = LinkedHashMap<String, Set<String>>()

        if (markerArray != null) {
            for (index in 0 until markerArray.length()) {
                val item = markerArray.optJSONObject(index) ?: continue
                val pillKey = item.optString("pillKey").takeIf { it.isNotBlank() } ?: continue
                val users = LinkedHashSet<String>()
                val values = item.optJSONArray("removedUsers") ?: continue
                for (userIndex in 0 until values.length()) {
                    val value = values.opt(userIndex)
                    val id = when (value) {
                        is JSONObject -> value.optString("id")
                        is String -> value
                        else -> ""
                    }.takeIf { it.isNotBlank() && it != "null" && it != "*" } ?: continue
                    users.add(id)
                }
                if (users.isNotEmpty()) markers[pillKey] = users
            }
        }

        val history = LinkedHashMap<String, RemovalEntry>()
        val historyArray = root?.optJSONArray("removalHistory")
        if (historyArray != null) {
            for (index in 0 until historyArray.length()) {
                val item = historyArray.optJSONObject(index) ?: continue
                val id = item.optString("id")
                val messageId = item.optString("messageId")
                val userId = item.optString("userId")
                val emojiKey = item.optString("emojiKey")
                if (id.isBlank() || messageId.isBlank() || userId.isBlank() || emojiKey.isBlank()) continue
                history[id] = RemovalEntry(
                    id = id,
                    guildId = item.optString("guildId").takeIf { it.isNotBlank() && it != "null" },
                    channelId = item.optString("channelId"),
                    messageId = messageId,
                    userId = userId,
                    emojiKey = emojiKey,
                    reactionType = item.optInt("reactionType", 0),
                    timestamp = item.optLong("timestamp", 0L),
                )
            }
        }

        val active = LinkedHashMap<String, ActiveEntry>()
        val activeArray = root?.optJSONArray("activeReactions")
        if (activeArray != null) {
            for (index in 0 until activeArray.length()) {
                val item = activeArray.optJSONObject(index) ?: continue
                val guildId = item.optString("guildId").takeIf { it.isNotBlank() && it != "null" }
                val channelId = item.optString("channelId")
                val messageId = item.optString("messageId")
                val userId = item.optString("userId")
                val emojiKey = item.optString("emojiKey")
                if (channelId.isBlank() || messageId.isBlank() || userId.isBlank() || emojiKey.isBlank()) continue
                val type = item.optInt("reactionType", 0)
                val identityKey = item.optString("identityKey").takeIf { it.isNotBlank() }
                    ?: reactionIdentityKey(guildId, channelId, messageId, emojiKey, type, userId)
                active[identityKey] = ActiveEntry(
                    identityKey,
                    guildId,
                    channelId,
                    messageId,
                    userId,
                    emojiKey,
                    type,
                )
            }
        }

        synchronized(removedUsersByPillKey) {
            removedUsersByPillKey.clear()
            removedUsersByPillKey.putAll(markers)
        }
        synchronized(removalHistory) {
            removalHistory.clear()
            removalHistory.putAll(history)
        }
        synchronized(activeReactions) {
            activeReactions.clear()
            activeReactions.putAll(active)
        }
        sessionStartedAt = root?.optLong("sessionStartedAt", 0L) ?: 0L

        val staleViews = synchronized(retainedPillViews) {
            val staleKeys = retainedPillViews.keys.filter { it !in markers.keys }
            staleKeys.mapNotNull { key -> retainedPillViews.remove(key) }
        }

        mainHandler.post {
            staleViews.forEach { view ->
                (view.parent as? ViewGroup)?.removeView(view)
                boundPills.remove(view)
                applyPillStyle(view, false, zeroCount = false)
            }
            for ((pill, key) in boundPills.entries.toList()) {
                val removed = isRemovedPill(key)
                applyPillStyle(pill, removed)
            }
            refreshUserRows()
            refreshPopupModel()
        }
    }

    fun clear() {
        synchronized(removedUsersByPillKey) { removedUsersByPillKey.clear() }
        synchronized(removalHistory) { removalHistory.clear() }
        synchronized(activeReactions) { activeReactions.clear() }
        synchronized(popupRemovedRowKeys) { popupRemovedRowKeys.clear() }
        synchronized(retainedPillViews) { retainedPillViews.clear() }
        mainHandler.post {
            for (pill in boundPills.keys.toList()) applyPillStyle(pill, false, zeroCount = false)
            for (row in userRows.keys.toList()) applyUserRowStyle(row, false)
        }
    }

    fun dispose() {
        unhooks.forEach { runCatching { it.unhook() } }
        unhooks.clear()

        synchronized(removedUsersByPillKey) { removedUsersByPillKey.clear() }
        synchronized(removalHistory) { removalHistory.clear() }
        synchronized(activeReactions) { activeReactions.clear() }
        synchronized(popupRemovedRowKeys) { popupRemovedRowKeys.clear() }

        lastPopupWidget = null
        lastPopupModel = null
        configureUiMethod = null
        popupContext = null
        bindingMessageId.remove()

        val retained = synchronized(retainedPillViews) {
            retainedPillViews.values.toList().also { retainedPillViews.clear() }
        }
        mainHandler.post {
            retained.forEach { view ->
                (view.parent as? ViewGroup)?.removeView(view)
                boundPills.remove(view)
                applyPillStyle(view, false, zeroCount = false)
            }
            for (pill in boundPills.keys.toList()) applyPillStyle(pill, false, zeroCount = false)
            for (row in userRows.keys.toList()) applyUserRowStyle(row, false)
            boundPills.clear()
            originalPillStyles.clear()
            userRows.clear()
            originalUserTextColors.clear()
            originalUserRowTints.clear()
            originalAvatarForegrounds.clear()
        }
    }

    private fun isRemovedPill(key: String): Boolean =
        synchronized(removedUsersByPillKey) { removedUsersByPillKey[key]?.isNotEmpty() == true }

    private fun reactionPillKey(messageId: String, emojiKey: String, type: Int) =
        "\$messageId|\$emojiKey|\$type"

    private fun rowIdentityKey(
        channelId: String,
        messageId: String,
        emojiKey: String,
        userId: String,
    ): String = listOf(channelId, messageId, emojiKey, userId).joinToString("\u0000")

    private fun reactionIdentityKey(
        guildId: String?,
        channelId: String,
        messageId: String,
        emojiKey: String,
        type: Int,
        userId: String,
    ): String = listOf(guildId.orEmpty(), channelId, messageId, emojiKey, type.toString(), userId)
        .joinToString("\u0000")

    // -----------------------------------------------------------------------------------------
    // Chat reaction pills (existing feature): preserve the red pill but never intercept Flux.
    // -----------------------------------------------------------------------------------------

    private fun hookReactionPills(dexFinder: DexFinder) {
        val accessoryClass = dexFinder.findClass {
            matcher { usingEqStrings("messageId", "accessoryDescription") }
        } ?: error("MessageAccessory not found")

        val accessoryMessageId = dexFinder.findMethod {
            matcher {
                declaredClass(accessoryClass.name)
                paramCount(0)
                returnType(String::class.java)
            }
        } ?: error("MessageAccessory.getMessageId not found")

        val emojiClass = dexFinder.findClass {
            matcher { addUsingString("MessageReactionEmoji(name=") }
        } ?: error("MessageReactionEmoji not found")

        val reactionInterface = dexFinder.findClass {
            matcher {
                modifiers(Modifier.INTERFACE)
                addMethod { name("getCount") }
                addMethod { returnType(emojiClass.name) }
            }
        } ?: error("ReactionView.Reaction not found")

        val getCount = reactionInterface.getMethod("getCount")
        val getEmoji = reactionInterface.methods.single { it.returnType == emojiClass }
        val getBurstCount = reactionInterface.methods.single {
            it.returnType == Int::class.javaPrimitiveType &&
                it.parameterCount == 0 &&
                it != getCount
        }

        val onBindViewHolder = dexFinder.findMethod {
            matcher {
                paramCount(2)
                usingEqStrings("reactions", "addNewReactionAccessibilityLabel")
            }
        } ?: error("MessageAccessoriesAdapter.onBindViewHolder not found")

        val adapterItems = onBindViewHolder.declaringClass.declaredFields
            .single { List::class.java.isAssignableFrom(it.type) && !Modifier.isStatic(it.modifiers) }
            .apply { isAccessible = true }

        val setReactions = dexFinder.findMethod {
            matcher {
                declaredClass(SHORTCUTS_FLEXBOX)
                usingEqStrings("reactions", "addNewReactionAccessibilityLabel")
            }
        } ?: error("ShortcutsFlexbox.setReactions not found")

        unhooks += onBindViewHolder.hook(
            before = { param ->
                val index = param.args.getOrNull(1) as? Int
                val item = index?.let {
                    (adapterItems.get(param.thisObject) as? List<*>)?.getOrNull(it)
                }
                if (item != null && accessoryClass.isInstance(item)) {
                    bindingMessageId.set(accessoryMessageId.invoke(item) as? String)
                } else {
                    bindingMessageId.remove()
                }
            },
            after = { bindingMessageId.remove() },
        )

        unhooks += setReactions.hook(
            before = { param ->
                val messageId = bindingMessageId.get() ?: return@hook
                val flexbox = param.thisObject as? ViewGroup ?: return@hook
                for (index in 0 until flexbox.childCount) {
                    val view = flexbox.getChildAt(index) as? LinearLayout ?: continue
                    val key = boundPills[view] ?: continue
                    if (key.startsWith("\$messageId|") && isRemovedPill(key)) {
                        synchronized(retainedPillViews) { retainedPillViews[key] = view }
                    }
                }
            },
            after = { param ->
                val flexbox = param.thisObject as? ViewGroup ?: return@hook
                val reactions = param.args.getOrNull(0) as? List<*> ?: emptyList<Any>()
                val messageId = bindingMessageId.get()
                val reactionPills = pills(reactions, getCount, getBurstCount, getEmoji)
                val currentKeys = LinkedHashSet<String>()

                for (index in 0 until flexbox.childCount) {
                    val view = flexbox.getChildAt(index) as? LinearLayout ?: continue
                    val pill = reactionPills.getOrNull(index)
                    if (pill == null || messageId == null || pill.count <= 0 || pill.emoji == null) {
                        boundPills.remove(view)
                        applyPillStyle(view, false, zeroCount = false)
                        continue
                    }
                    val emoji = emojiKey(pill.emoji) ?: continue
                    val key = reactionPillKey(messageId, emoji, if (pill.burst) 1 else 0)
                    currentKeys.add(key)
                    boundPills[view] = key
                    applyPillStyle(view, isRemovedPill(key))
                }

                if (messageId != null) {
                    val prefix = "\$messageId|"
                    val retained = synchronized(retainedPillViews) {
                        retainedPillViews.entries
                            .filter { it.key.startsWith(prefix) && isRemovedPill(it.key) }
                            .map { it.key to it.value }
                    }
                    for ((key, view) in retained) {
                        if (key in currentKeys) continue
                        if (view.parent !== flexbox) {
                            (view.parent as? ViewGroup)?.removeView(view)
                            runCatching { flexbox.addView(view) }.onFailure {
                                XposedBridge.log("Reaction Logs: could not retain zero-count reaction pill: \$it")
                            }
                        }
                        boundPills[view] = key
                        applyPillStyle(view, true, zeroCount = true)
                    }
                }
            },
        )
    }

    // -----------------------------------------------------------------------------------------
    // Existing Discord Manage Reactions popup integration.
    // -----------------------------------------------------------------------------------------

    private fun hookReactionPopup(dexFinder: DexFinder) {
        val widgetClass = dexFinder.classLoader.loadClass(MANAGE_REACTIONS_WIDGET)
        val configureUi = widgetClass.declaredMethods.firstOrNull {
            it.name == "configureUI" && it.parameterCount == 1
        } ?: error("WidgetManageReactions.configureUI(model) not found")
        configureUi.isAccessible = true
        configureUiMethod = configureUi

        unhooks += configureUi.hook(before = { param ->
            val model = param.args.getOrNull(0) ?: return@hook
            val widget = param.thisObject
            lastPopupWidget = WeakReference(widget)
            lastPopupModel = model
            val augmented = augmentPopupModel(widget, model, dexFinder.classLoader)
            if (augmented !== model) param.args[0] = augmented
        })

        val itemClass = dexFinder.classLoader.loadClass(REACTION_USER_ITEM)
        val typedBinder = runCatching {
            dexFinder.findMethod {
                matcher {
                    name("onConfigure")
                    paramCount(2)
                    paramTypes("int", itemClass.name)
                }
            }
        }.getOrNull()

        if (typedBinder != null) {
            unhooks += typedBinder.hook(after = { param ->
                val data = param.args.getOrNull(1) ?: return@hook
                val view = holderItemView(param.thisObject) ?: return@hook
                bindReactionUserRow(view, data)
            })
        } else {
            // Some builds route through the erased RecyclerView holder signature.
            val holderBase = dexFinder.classLoader.loadClass(MG_RECYCLER_VIEW_HOLDER)
            val genericBinder = generateSequence(holderBase) { it.superclass }
                .flatMap { it.declaredMethods.asSequence() }
                .firstOrNull {
                    it.name == "onConfigure" &&
                        it.parameterCount == 2 &&
                        it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                        it.parameterTypes[1] == Any::class.java
                } ?: error("ReactionUserItem row binder not found")

            unhooks += genericBinder.hook(after = { param ->
                val data = param.args.getOrNull(1) ?: return@hook
                if (!itemClass.isInstance(data)) return@hook
                val view = holderItemView(param.thisObject) ?: return@hook
                bindReactionUserRow(view, data)
            })
        }

        // Re-resolve users after Discord's user cache finishes a fetch. This prevents a
        // history record from being lost just because its User object was not cached yet.
        val storeUserClass = dexFinder.classLoader.loadClass(STORE_USER)
        val snapshotData = storeUserClass.declaredMethods.firstOrNull {
            it.name == "snapshotData" && it.parameterCount == 0
        }
        if (snapshotData != null) {
            snapshotData.isAccessible = true
            unhooks += snapshotData.hook(after = {
                mainHandler.post { refreshPopupModel() }
            })
        }

        val destroyView = generateSequence(widgetClass) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { it.name == "onDestroyView" && it.parameterCount == 0 }
        if (destroyView != null) {
            destroyView.isAccessible = true
            unhooks += destroyView.hook(after = { param ->
                if (lastPopupWidget?.get() === param.thisObject) {
                    lastPopupWidget = null
                    lastPopupModel = null
                    synchronized(popupRemovedRowKeys) { popupRemovedRowKeys.clear() }
                    popupContext = null
                }
            })
        }

        XposedBridge.log("Reaction Logs: hooked the existing Manage Reactions popup and user row binder")
    }

    private fun augmentPopupModel(widget: Any, model: Any, loader: ClassLoader): Any {
        synchronized(popupRemovedRowKeys) { popupRemovedRowKeys.clear() }
        val provider = readMember(widget, "modelProvider") ?: return model
        val channelId = readMember(provider, "channelId")?.toString() ?: return model
        val messageId = readMember(provider, "messageId")?.toString() ?: return model
        val channelLong = channelId.toLongOrNull() ?: return model
        val messageLong = messageId.toLongOrNull() ?: return model

        val reactionItems = readMember(model, "reactionItems") as? List<*> ?: return model
        val selected = reactionItems.firstOrNull { readMember(it, "isSelected") == true } ?: return model
        val reaction = readMember(selected, "reaction") ?: return model
        val emoji = readMember(reaction, "emoji") ?: return model
        val selectedEmojiKey = emojiKey(emoji) ?: return model
        val guildId = resolveGuildId(loader, channelLong)
        val context = PopupContext(guildId, channelId, messageId, selectedEmojiKey)
        popupContext = context

        val originalItems = readMember(model, "userItems") as? List<*> ?: return model
        val userItemClass = runCatching { loader.loadClass(REACTION_USER_ITEM) }.getOrNull()
            ?: return model

        val existingIds = LinkedHashSet<String>()
        for (item in originalItems) {
            if (item != null && userItemClass.isInstance(item)) {
                extractUserId(item)?.let(existingIds::add)
            }
        }

        val activeIds = synchronized(activeReactions) {
            activeReactions.values.filter { active ->
                active.channelId == channelId &&
                    active.messageId == messageId &&
                    active.emojiKey == selectedEmojiKey &&
                    (active.guildId.isNullOrBlank() || guildId.isNullOrBlank() || active.guildId == guildId)
            }.mapTo(LinkedHashSet()) { it.userId }
        }

        val matchingHistory = synchronized(removalHistory) {
            removalHistory.values.filter { entry ->
                entry.messageId == messageId &&
                    (entry.channelId.isBlank() || entry.channelId == channelId) &&
                    entry.emojiKey == selectedEmojiKey &&
                    (entry.guildId.isNullOrBlank() || guildId.isNullOrBlank() || entry.guildId == guildId)
            }.sortedBy { it.timestamp }
        }

        val usersToFetch = LinkedHashSet<Long>()
        val output = ArrayList<Any>(originalItems.size + matchingHistory.size)
        originalItems.filterNotNullTo(output)
        val highlightedRows = LinkedHashSet<String>()

        for (entry in matchingHistory) {
            // A re-add event in this session wins over an older removal record.
            if (entry.userId in activeIds) continue

            val rowKey = rowIdentityKey(channelId, messageId, selectedEmojiKey, entry.userId)
            if (entry.userId in existingIds) {
                // A row can remain briefly in a stale list between Flux event and Store emission.
                // Only override it when this plugin observed the removal during this app session.
                if (entry.timestamp >= sessionStartedAt) highlightedRows.add(rowKey)
                continue
            }

            val userIdLong = entry.userId.toLongOrNull() ?: continue
            val user = resolveUser(loader, userIdLong)
            if (user == null) {
                usersToFetch.add(userIdLong)
                continue
            }
            val member = guildId?.let { resolveGuildMember(loader, it, userIdLong) }
            val row = createReactionUserItem(
                loader,
                user,
                channelLong,
                messageLong,
                emoji,
                member,
            ) ?: continue
            output.add(row)
            existingIds.add(entry.userId)
            highlightedRows.add(rowKey)
        }

        if (usersToFetch.isNotEmpty()) fetchUsers(loader, usersToFetch)
        synchronized(popupRemovedRowKeys) {
            popupRemovedRowKeys.clear()
            popupRemovedRowKeys.addAll(highlightedRows)
        }
        refreshUserRows()

        if (output.size == originalItems.size) return model
        val copy = model.javaClass.methods.firstOrNull {
            it.name == "copy" && it.parameterCount == 2
        }
        if (copy != null) {
            return runCatching { copy.invoke(model, reactionItems, output) }.getOrNull() ?: model
        }

        val constructor = model.javaClass.declaredConstructors.firstOrNull {
            it.parameterCount == 2 &&
                List::class.java.isAssignableFrom(it.parameterTypes[0]) &&
                List::class.java.isAssignableFrom(it.parameterTypes[1])
        } ?: return model
        return runCatching {
            constructor.isAccessible = true
            constructor.newInstance(reactionItems, output)
        }.getOrNull() ?: model
    }

    private fun refreshPopupModel() {
        val widget = lastPopupWidget?.get() ?: return
        val model = lastPopupModel ?: return
        val method = configureUiMethod ?: return
        val view = runCatching {
            widget.javaClass.methods.firstOrNull {
                it.name == "getView" && it.parameterCount == 0
            }?.invoke(widget)
        }.getOrNull()
        if (view == null) return

        runCatching { method.invoke(widget, model) }.onFailure {
            XposedBridge.log("Reaction Logs: could not refresh the open reaction popup: \$it")
        }
    }

    private fun bindReactionUserRow(view: View, data: Any) {
        val context = popupContext
        val user = readMember(data, "user")
        val userId = extractUserId(user) ?: extractUserId(data)
        if (context == null || userId.isNullOrBlank()) {
            userRows.remove(view)
            applyUserRowStyle(view, false)
            return
        }

        val key = rowIdentityKey(context.channelId, context.messageId, context.emojiKey, userId)
        userRows[view] = RowBinding(key, userId)
        mainHandler.post {
            val binding = userRows[view] ?: return@post
            if (binding.rowKey != key || binding.userId != userId) return@post
            val highlighted = synchronized(popupRemovedRowKeys) { key in popupRemovedRowKeys }
            applyUserRowStyle(view, highlighted)
        }
    }

    private fun refreshUserRows() {
        for ((view, binding) in userRows.entries.toList()) {
            val highlighted = synchronized(popupRemovedRowKeys) { binding.rowKey in popupRemovedRowKeys }
            applyUserRowStyle(view, highlighted)
        }
    }

    private fun resolveStore(loader: ClassLoader, getterName: String): Any? = runCatching {
        val stream = loader.loadClass(STORE_STREAM)
        val getter = stream.methods.firstOrNull {
            Modifier.isStatic(it.modifiers) && it.name == getterName && it.parameterCount == 0
        } ?: return@runCatching null
        getter.invoke(null)
    }.getOrNull()

    private fun resolveGuildId(loader: ClassLoader, channelId: Long): String? = runCatching {
        val channels = resolveStore(loader, "getChannels") ?: return@runCatching null
        val channel = channels.javaClass.methods.firstOrNull {
            it.name == "getChannel" && it.parameterCount == 1
        }?.invoke(channels, channelId) ?: return@runCatching null
        readMember(channel, "guildId")?.toString()?.takeIf { it.isNotBlank() && it != "0" && it != "null" }
    }.getOrNull()

    private fun resolveGuildMember(loader: ClassLoader, guildId: String, userId: Long): Any? = runCatching {
        val guilds = resolveStore(loader, "getGuilds") ?: return@runCatching null
        val members = guilds.javaClass.methods.firstOrNull {
            it.name == "getMembers" && it.parameterCount == 0
        }?.invoke(guilds) as? Map<*, *> ?: return@runCatching null
        val guildMembers = members[guildId.toLongOrNull()] as? Map<*, *>
            ?: members[guildId] as? Map<*, *>
            ?: return@runCatching null
        guildMembers[userId] ?: guildMembers[userId.toString()]
    }.getOrNull()

    private fun resolveUser(loader: ClassLoader, userId: Long): Any? = runCatching {
        val users = resolveStore(loader, "getUsers") ?: return@runCatching null
        val map = users.javaClass.methods.firstOrNull {
            it.name == "getUsers" && it.parameterCount == 0
        }?.invoke(users) as? Map<*, *> ?: return@runCatching null
        map[userId] ?: map[userId.toString()]
    }.getOrNull()

    private fun fetchUsers(loader: ClassLoader, userIds: Set<Long>) {
        val pending = synchronized(requestedUserFetches) {
            userIds.filterTo(LinkedHashSet()) { requestedUserFetches.add(it) }
        }
        if (pending.isEmpty()) return

        runCatching {
            val users = resolveStore(loader, "getUsers") ?: return@runCatching
            val method = users.javaClass.methods.firstOrNull {
                it.name == "fetchUsers" &&
                    it.parameterCount == 1 &&
                    List::class.java.isAssignableFrom(it.parameterTypes[0])
            } ?: return@runCatching
            method.invoke(users, pending.toList())
        }.onFailure {
            XposedBridge.log("Reaction Logs: could not request missing removed users: \$it")
        }
    }

    private fun createReactionUserItem(
        loader: ClassLoader,
        user: Any,
        channelId: Long,
        messageId: Long,
        emoji: Any,
        guildMember: Any?,
    ): Any? = runCatching {
        val itemClass = loader.loadClass(REACTION_USER_ITEM)
        val constructor = itemClass.declaredConstructors.firstOrNull { it.parameterCount == 6 }
            ?: return@runCatching null
        constructor.isAccessible = true
        constructor.newInstance(user, channelId, messageId, emoji, false, guildMember)
    }.onFailure {
        XposedBridge.log("Reaction Logs: could not construct native removed-user row: \$it")
    }.getOrNull()

    private fun readMember(target: Any?, name: String): Any? {
        if (target == null) return null
        val suffix = name.replaceFirstChar { it.uppercase() }
        target.javaClass.methods.firstOrNull {
            it.parameterCount == 0 && it.name in setOf(name, "get\$suffix", "is\$suffix")
        }?.let { method ->
            runCatching { method.invoke(target) }.getOrNull()?.let { return it }
        }

        var current: Class<*>? = target.javaClass
        while (current != null) {
            val field = runCatching { current.getDeclaredField(name) }.getOrNull()
            if (field != null && !Modifier.isStatic(field.modifiers)) {
                runCatching {
                    field.isAccessible = true
                    field.get(target)
                }.getOrNull()?.let { return it }
            }
            current = current.superclass
        }
        return null
    }

    private fun extractUserId(value: Any?, depth: Int = 0): String? {
        if (value == null || depth > 3) return null
        val typeName = value.javaClass.name.lowercase()

        if (typeName.contains("user") || typeName.contains("member")) {
            readMember(value, "id")?.toString()?.takeIf { it.isNotBlank() && it != "null" }?.let { return it }
        }
        readMember(value, "user")?.let { nested ->
            extractUserId(nested, depth + 1)?.let { return it }
        }
        readMember(value, "member")?.let { nested ->
            extractUserId(nested, depth + 1)?.let { return it }
        }
        readMember(value, "userId")?.toString()?.takeIf { it.isNotBlank() && it != "null" }?.let { return it }
        readMember(value, "userID")?.toString()?.takeIf { it.isNotBlank() && it != "null" }?.let { return it }
        return null
    }

    private fun holderItemView(holder: Any): View? {
        runCatching {
            holder.javaClass.getField("itemView").get(holder) as? View
        }.getOrNull()?.let { return it }

        var current: Class<*>? = holder.javaClass
        while (current != null) {
            val field = runCatching { current.getDeclaredField("itemView") }.getOrNull()
            if (field != null) {
                return runCatching {
                    field.isAccessible = true
                    field.get(holder) as? View
                }.getOrNull()
            }
            current = current.superclass
        }
        return null
    }

    private fun applyUserRowStyle(view: View, highlighted: Boolean) {
        if (highlighted) {
            if (!originalUserRowTints.containsKey(view)) {
                originalUserRowTints[view] = view.backgroundTintList
            }
            view.backgroundTintList = ColorStateList.valueOf(REMOVED_TINT)
        } else if (originalUserRowTints.containsKey(view)) {
            view.backgroundTintList = originalUserRowTints.remove(view)
        }

        visitViews(view) { child ->
            if (child is TextView) {
                if (highlighted) {
                    if (!originalUserTextColors.containsKey(child)) {
                        originalUserTextColors[child] = child.textColors
                    }
                    child.setTextColor(REMOVED_RED)
                } else if (originalUserTextColors.containsKey(child)) {
                    originalUserTextColors.remove(child)?.let(child::setTextColor)
                }
            }

            if (highlighted && child !== view && isAvatarView(child)) {
                if (!originalAvatarForegrounds.containsKey(child)) {
                    originalAvatarForegrounds[child] = child.foreground
                }
                val stroke = (2f * child.resources.displayMetrics.density).toInt().coerceAtLeast(1)
                val ring = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.TRANSPARENT)
                    setStroke(stroke, REMOVED_RED)
                }
                child.foreground = originalAvatarForegrounds[child]?.let { LayerDrawable(arrayOf(it, ring)) } ?: ring
            } else if (!highlighted && originalAvatarForegrounds.containsKey(child)) {
                child.foreground = originalAvatarForegrounds.remove(child)
            }
        }
    }

    private fun isAvatarView(view: View): Boolean {
        val name = view.javaClass.name.lowercase()
        if (name.contains("avatar") || name.contains("vitoimage")) return true
        if (view !is ImageView) return false
        val minSize = (20f * view.resources.displayMetrics.density).toInt()
        val width = if (view.width > 0) view.width else view.layoutParams?.width ?: 0
        val height = if (view.height > 0) view.height else view.layoutParams?.height ?: 0
        return (width <= 0 || width >= minSize) && (height <= 0 || height >= minSize)
    }

    private fun visitViews(view: View, action: (View) -> Unit) {
        action(view)
        if (view !is ViewGroup) return
        for (index in 0 until view.childCount) visitViews(view.getChildAt(index), action)
    }

    private fun applyPillStyle(view: LinearLayout, removed: Boolean, zeroCount: Boolean = false) {
        val count = countText(view)
        val original = originalPillStyles.getOrPut(view) {
            OriginalPillStyle(view.backgroundTintList, count?.textColors)
        }
        if (removed) {
            view.backgroundTintList = ColorStateList.valueOf(REMOVED_TINT)
            count?.setTextColor(REMOVED_RED)
            if (zeroCount) count?.text = "0"
        } else {
            view.backgroundTintList = original.backgroundTint
            original.countColors?.let { count?.setTextColor(it) }
        }
    }

    private fun countText(view: ViewGroup): TextView? {
        for (index in 0 until view.childCount) {
            val child = view.getChildAt(index)
            if (child is TextSwitcher) return child.currentView as? TextView
        }
        return null
    }

    private fun pills(
        reactions: List<*>,
        getCount: Method,
        getBurstCount: Method,
        getEmoji: Method,
    ): List<Pill> = reactions.flatMap { reaction ->
        if (reaction == null) return@flatMap emptyList()
        val count = getCount.invoke(reaction) as? Int ?: 0
        val burstCount = getBurstCount.invoke(reaction) as? Int ?: 0
        val emoji = getEmoji.invoke(reaction)
        when {
            burstCount > 0 && count > 0 -> listOf(Pill(emoji, true, burstCount), Pill(emoji, false, count))
            burstCount > 0 -> listOf(Pill(emoji, true, burstCount))
            count > 0 -> listOf(Pill(emoji, false, count))
            else -> emptyList()
        }
    }

    private fun emojiKey(emoji: Any): String? {
        val id = runCatching {
            emoji.javaClass.methods.firstOrNull { it.name == "getId" && it.parameterCount == 0 }
                ?.invoke(emoji)?.toString()?.takeIf { it.isNotBlank() && it != "null" }
        }.getOrNull()
        val name = runCatching {
            emoji.javaClass.methods.firstOrNull { it.name == "getName" && it.parameterCount == 0 }
                ?.invoke(emoji)?.toString()?.takeIf { it.isNotBlank() && it != "null" }
        }.getOrNull()

        if (id != null) return "custom:\$id"
        if (name != null) return "unicode:\${Normalizer.normalize(name, Normalizer.Form.NFC)}"

        val text = emoji.toString()
        val parsedId = ID_PATTERN.find(text)?.groupValues?.get(1)?.takeIf { it.isNotBlank() && it != "null" }
        val parsedName = NAME_PATTERN.find(text)?.groupValues?.get(1)?.takeIf { it.isNotBlank() && it != "null" }
        return when {
            parsedId != null -> "custom:\$parsedId"
            parsedName != null -> "unicode:\${Normalizer.normalize(parsedName, Normalizer.Form.NFC)}"
            else -> null
        }
    }

    private companion object {
        val ID_PATTERN = Regex("""(?:[(]|, )id=([^,)]*)""")
        val NAME_PATTERN = Regex("""(?:[(]|, )name=([^,)]*)""")
    }
}

private inline fun Method.hook(
    crossinline before: (XC_MethodHook.MethodHookParam) -> Unit = {},
    crossinline after: (XC_MethodHook.MethodHookParam) -> Unit = {},
): XC_MethodHook.Unhook = XposedBridge.hookMethod(this, object : XC_MethodHook() {
    override fun beforeHookedMethod(param: MethodHookParam) = before(param)
    override fun afterHookedMethod(param: MethodHookParam) = after(param)
})
