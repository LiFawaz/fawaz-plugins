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
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.lang.ref.WeakReference
import java.text.Normalizer
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.WeakHashMap

private const val SHORTCUTS_FLEXBOX = "com.discord.reactions.ShortcutsFlexbox"
private const val MANAGE_REACTIONS_MODEL_PROVIDER =
    "com.discord.widgets.chat.managereactions.ManageReactionsModelProvider"
private const val MANAGE_REACTIONS_WIDGET =
    "com.discord.widgets.chat.managereactions.WidgetManageReactions"
private const val REACTION_USER_ITEM =
    "com.discord.widgets.chat.managereactions.ManageReactionsResultsAdapter\$ReactionUserItem"
private const val MG_RECYCLER_VIEW_HOLDER =
    "com.discord.utilities.mg_recycler.MGRecyclerViewHolder"

private data class ReactionModelKey(
    val channelId: String,
    val messageId: String,
    val emojiKey: String,
    val reactionType: Int,
)

private val REMOVED_RED = Color.rgb(237, 66, 69)
private val REMOVED_TINT = Color.argb(48, 237, 66, 69)

internal class ReactionLogHooks(
    dexFinder: DexFinder,
    @Suppress("UNUSED_PARAMETER") bridge: ValencedBridge,
) {
    private data class Pill(
        val reaction: Any,
        val emoji: Any?,
        val burst: Boolean,
        val count: Int,
    )
    private data class OriginalPillStyle(
        val backgroundTint: ColorStateList?,
        val countColors: ColorStateList?,
        val countText: CharSequence?,
        val countVisibility: Int?,
        val countSwitcherVisibility: Int?,
        val labelVisibilities: Map<Int, Int>,
    )
    private data class UserRowBinding(
        val guildId: String?,
        val channelId: String,
        val messageId: String,
        val emojiKey: String,
        val reactionType: Int,
        val userId: String,
    )
    private data class RemovalEntry(
        val id: String,
        val guildId: String?,
        val channelId: String,
        val messageId: String,
        val userId: String,
        val emojiKey: String,
        val emojiId: String?,
        val emojiName: String?,
        val emojiSrc: String?,
        val emojiDisplayName: String?,
        val emojiAnimated: Boolean?,
        val reactionType: Int,
        val timestamp: Long?,
    )
    private data class ActiveEntry(
        val guildId: String?,
        val channelId: String,
        val messageId: String,
        val userId: String,
        val emojiKey: String,
        val reactionType: Int,
    )

    private val removedUsersByKey = LinkedHashMap<String, Set<String>>()
    private val removalHistory = LinkedHashMap<String, RemovalEntry>()
    private val activeReactions = LinkedHashSet<ActiveEntry>()
    private val popupRemovedUsers = LinkedHashSet<String>()
    @Volatile private var detailsGuildId: String? = null
    @Volatile private var detailsChannelId: String? = null
    @Volatile private var detailsEmojiKey: String? = null
    @Volatile private var detailsReactionType: Int = 0
    private var lastPopupWidget: WeakReference<Any>? = null
    private var lastPopupModel: Any? = null
    private var configurePopupMethod: Method? = null
    private var popupRetryKey: String? = null
    private var popupRetryAttempts = 0
    private var popupRetryRunnable: Runnable? = null
    // Keep reaction data models, not Android views: ShortcutsFlexbox recycles its
    // ten ReactionView children for different emoji during every setReactions call.
    private val reactionTemplates = LinkedHashMap<ReactionModelKey, Any>()
    private val syntheticReactionModels = WeakHashMap<Any, Boolean>()
    private val syntheticPillViews = WeakHashMap<LinearLayout, Boolean>()
    private val boundPills = WeakHashMap<LinearLayout, String>()
    private val pillCounts = WeakHashMap<LinearLayout, Int>()
    private val originalPillStyles = WeakHashMap<LinearLayout, OriginalPillStyle>()
    private val userRows = WeakHashMap<View, UserRowBinding>()
    private val originalUserTextColors = WeakHashMap<TextView, ColorStateList>()
    private val originalUserRowTints = WeakHashMap<View, ColorStateList?>()
    @Volatile private var detailsMessageId: String? = null
    private val bindingMessageId = ThreadLocal<String?>()
    private val bindingChannelId = ThreadLocal<String?>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val unhooks = mutableListOf<XC_MethodHook.Unhook>()

    init {
        hookReactionPills(dexFinder)
        runCatching { hookReactionUserRows(dexFinder) }.onFailure {
            XposedBridge.log("Reaction Logs: reaction-user row hook unavailable: $it")
        }
    }

    fun setRemoved(json: String) {
        val decoded = JSONTokener(json).nextValue()
        val root = decoded as? JSONObject
        val legacy = decoded as? JSONArray
        val markerArray = root?.optJSONArray("activeRemovals") ?: legacy
        val parsedMarkers = LinkedHashMap<String, Set<String>>()
        if (markerArray != null) {
            for (index in 0 until markerArray.length()) {
                val item = markerArray.optJSONObject(index) ?: continue
                val key = item.optString("pillKey").takeIf { it.isNotBlank() } ?: continue
                val users = LinkedHashSet<String>()
                val userArray = item.optJSONArray("removedUsers")
                if (userArray != null) {
                    for (userIndex in 0 until userArray.length()) {
                        val user = userArray.opt(userIndex)
                        val id = when (user) {
                            is JSONObject -> user.optString("id")
                            is String -> user
                            else -> ""
                        }.takeIf { it.isNotBlank() && it != "null" && it != "*" } ?: continue
                        users.add(id)
                    }
                }
                if (users.isNotEmpty()) parsedMarkers[key] = users
            }
        }

        val parsedHistory = LinkedHashMap<String, RemovalEntry>()
        val historyArray = root?.optJSONArray("removalHistory")
        if (historyArray != null) {
            for (index in 0 until historyArray.length()) {
                val item = historyArray.optJSONObject(index) ?: continue
                val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                val channelId = item.optString("channelId")
                val messageId = item.optString("messageId")
                val userId = item.optString("userId")
                val emojiKey = item.optString("emojiKey")
                if (messageId.isBlank() || userId.isBlank() || emojiKey.isBlank()) continue
                parsedHistory[id] = RemovalEntry(
                    id = id,
                    guildId = item.optString("guildId").takeIf { it.isNotBlank() && it != "null" },
                    channelId = channelId,
                    messageId = messageId,
                    userId = userId,
                    emojiKey = emojiKey,
                    emojiId = item.optString("emojiId").takeIf { it.isNotBlank() && it != "null" },
                    emojiName = item.optString("emojiName").takeIf { it.isNotBlank() && it != "null" },
                    emojiSrc = item.optString("emojiSrc").takeIf { it.isNotBlank() && it != "null" },
                    emojiDisplayName = item.optString("emojiDisplayName").takeIf { it.isNotBlank() && it != "null" },
                    emojiAnimated = if (item.isNull("emojiAnimated")) null else item.optBoolean("emojiAnimated"),
                    reactionType = item.optInt("reactionType", 0),
                    timestamp = if (item.isNull("timestamp")) null else item.optLong("timestamp"),
                )
            }
        }

        val parsedActive = LinkedHashSet<ActiveEntry>()
        val activeArray = root?.optJSONArray("activeReactions")
        if (activeArray != null) {
            for (index in 0 until activeArray.length()) {
                val item = activeArray.optJSONObject(index) ?: continue
                val channelId = item.optString("channelId")
                val messageId = item.optString("messageId")
                val userId = item.optString("userId")
                val emojiKey = item.optString("emojiKey")
                if (channelId.isBlank() || messageId.isBlank() || userId.isBlank() || emojiKey.isBlank()) continue
                parsedActive.add(
                    ActiveEntry(
                        guildId = item.optString("guildId").takeIf { it.isNotBlank() && it != "null" },
                        channelId = channelId,
                        messageId = messageId,
                        userId = userId,
                        emojiKey = emojiKey,
                        reactionType = item.optInt("reactionType", 0),
                    ),
                )
            }
        }

        val retainedTemplateKeys = LinkedHashSet<ReactionModelKey>()
        parsedHistory.values.forEach { entry ->
            if (entry.channelId.isNotBlank()) {
                retainedTemplateKeys.add(modelKey(entry.channelId, entry.messageId, entry.emojiKey, entry.reactionType))
            }
        }
        parsedActive.forEach { active ->
            retainedTemplateKeys.add(modelKey(active.channelId, active.messageId, active.emojiKey, active.reactionType))
        }
        synchronized(reactionTemplates) {
            val iterator = reactionTemplates.keys.iterator()
            while (iterator.hasNext()) {
                val cached = iterator.next()
                val needed = retainedTemplateKeys.any { it == cached } ||
                    parsedHistory.values.any {
                        it.channelId.isBlank() && it.messageId == cached.messageId &&
                            it.emojiKey == cached.emojiKey && it.reactionType == cached.reactionType
                    }
                if (!needed) iterator.remove()
            }
        }

        synchronized(removedUsersByKey) {
            removedUsersByKey.clear()
            removedUsersByKey.putAll(parsedMarkers)
        }
        synchronized(removalHistory) {
            removalHistory.clear()
            removalHistory.putAll(parsedHistory)
        }
        synchronized(activeReactions) {
            activeReactions.clear()
            activeReactions.addAll(parsedActive)
        }

        XposedBridge.log(
            "Reaction Logs: native snapshot synced (removedPills=${parsedMarkers.size}, history=${parsedHistory.size}, active=${parsedActive.size})",
        )
        mainHandler.post {
            for ((pill, key) in boundPills.entries.toList()) {
                val removed = isRemoved(key)
                if (!removed && syntheticPillViews.containsKey(pill)) {
                    pill.visibility = View.GONE
                    syntheticPillViews.remove(pill)
                    boundPills.remove(pill)
                    pillCounts.remove(pill)
                    restorePillStyle(pill)
                } else {
                    applyPillStyle(pill, removed, pillCounts[pill] == 0)
                }
            }
            refreshUserRows()
            refreshPopupModel()
        }
    }

    private fun refreshPopupModel() {
        val widget = lastPopupWidget?.get() ?: return
        val model = lastPopupModel ?: return
        val method = configurePopupMethod ?: return
        runCatching {
            method.invoke(widget, model)
        }.onFailure {
            XposedBridge.log("Reaction Logs: could not refresh the open reaction popup: $it")
        }
    }

    fun clear() {
        synchronized(removedUsersByKey) { removedUsersByKey.clear() }
        synchronized(removalHistory) { removalHistory.clear() }
        synchronized(activeReactions) { activeReactions.clear() }
        synchronized(popupRemovedUsers) { popupRemovedUsers.clear() }
        synchronized(reactionTemplates) { reactionTemplates.clear() }
        synchronized(syntheticReactionModels) { syntheticReactionModels.clear() }
        mainHandler.post {
            for (pill in boundPillViews()) {
                if (syntheticPillViews.containsKey(pill)) pill.visibility = View.GONE
                restorePillStyle(pill)
            }
            boundPills.clear()
            pillCounts.clear()
            syntheticPillViews.clear()
            for (row in userRows.keys.toList()) applyUserRowStyle(row, false)
        }
    }

    fun dispose() {
        unhooks.forEach { it.unhook() }
        unhooks.clear()
        synchronized(removedUsersByKey) { removedUsersByKey.clear() }
        synchronized(removalHistory) { removalHistory.clear() }
        synchronized(activeReactions) { activeReactions.clear() }
        detailsMessageId = null
        detailsGuildId = null
        detailsChannelId = null
        detailsEmojiKey = null
        detailsReactionType = 0
        lastPopupWidget = null
        lastPopupModel = null
        configurePopupMethod = null
        cancelPopupRetry()
        synchronized(popupRemovedUsers) { popupRemovedUsers.clear() }
        synchronized(reactionTemplates) { reactionTemplates.clear() }
        synchronized(syntheticReactionModels) { syntheticReactionModels.clear() }
        bindingMessageId.remove()
        bindingChannelId.remove()
        mainHandler.post {
            for (pill in boundPillViews()) {
                if (syntheticPillViews.containsKey(pill)) pill.visibility = View.GONE
                restorePillStyle(pill)
            }
            boundPills.clear()
            pillCounts.clear()
            syntheticPillViews.clear()
            for (row in userRows.keys.toList()) applyUserRowStyle(row, false)
            originalPillStyles.clear()
            userRows.clear()
            originalUserTextColors.clear()
            originalUserRowTints.clear()
        }
    }

    private fun isRemoved(key: String): Boolean =
        synchronized(removedUsersByKey) { key in removedUsersByKey }

    /**
     * Inject a zero-count Reaction model into Discord's own setReactions input.
     * ShortcutsFlexbox reuses its child ReactionViews by index and hides the
     * remainder; holding and reattaching an old LinearLayout therefore rebinds
     * the wrong emoji when Discord recycles that child.
     */
    private fun hookReactionPills(dexFinder: DexFinder) {
        val accessoryClass = dexFinder.findClass {
            matcher { usingEqStrings("messageId", "accessoryDescription") }
        } ?: error("MessageAccessory not found")

        // MessageAccessory also exposes accessoryDescription as a String.
        // Match the mangled Kotlin value-class getter by name, not just its
        // return type, or we can accidentally bind the description as messageId.
        val accessoryMessageId = generateSequence(accessoryClass) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull {
                it.name.startsWith("getMessageId") &&
                    it.parameterCount == 0 &&
                    it.returnType == String::class.java
            }?.apply { isAccessible = true }
            ?: error("MessageAccessory.getMessageId(value-class getter) not found")

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
                it.parameterCount == 0 && it != getCount
        }
        val isBurstReaction = reactionInterface.methods.firstOrNull {
            it.name == "isBurstReaction" && it.parameterCount == 0
        } ?: error("ReactionView.Reaction.isBurstReaction not found")

        val onBindViewHolder = dexFinder.findMethod {
            matcher {
                paramCount(2)
                usingEqStrings("reactions", "addNewReactionAccessibilityLabel")
            }
        } ?: error("MessageAccessoriesAdapter.onBindViewHolder not found")

        // Ask DexFinder for the actual adapter list field instead of assuming
        // that there is exactly one List field in this Discord version.
        val adapterItems = dexFinder.findField {
            matcher {
                declaredClass(onBindViewHolder.declaringClass.name)
                type(List::class.java)
            }
        } ?: error("MessageAccessoriesAdapter.items not found")

        // MessageAccessory itself has messageId and accessoryDescription only.
        // The current channel lives on MessageAccessoriesAdapter.setItems(...).
        val adapterChannelId = onBindViewHolder.declaringClass.declaredFields
            .firstOrNull { it.name == "channelId" }
            ?.apply { isAccessible = true }
            ?: error("MessageAccessoriesAdapter.channelId not found")

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
                    bindingMessageId.set(accessoryMessageId.invoke(item)?.toString())
                    bindingChannelId.set(
                        snowflakeId(adapterChannelId.get(param.thisObject))
                            ?: snowflakeId(readMember(item, "channelId")),
                    )
                } else {
                    bindingMessageId.remove()
                    bindingChannelId.remove()
                }
            },
            after = {
                bindingMessageId.remove()
                bindingChannelId.remove()
            },
        )

        unhooks += setReactions.hook(
            before = { param ->
                val messageId = bindingMessageId.get() ?: return@hook
                val channelId = bindingChannelId.get()
                val flexbox = param.thisObject as? ViewGroup ?: return@hook
                for (index in 0 until flexbox.childCount) {
                    val pillView = flexbox.getChildAt(index) as? LinearLayout ?: continue
                    // Restore the native state before Discord reuses this view for
                    // another reaction. Never preserve its old emoji binding.
                    restorePillStyle(pillView)
                    boundPills.remove(pillView)
                    pillCounts.remove(pillView)
                    syntheticPillViews.remove(pillView)
                }

                val incoming = param.args.getOrNull(0) as? List<*> ?: emptyList<Any>()
                val rendered = pills(incoming, getCount, getBurstCount, getEmoji, isBurstReaction)
                val currentKeys = rendered.mapNotNull { pill ->
                    val emoji = pill.emoji ?: return@mapNotNull null
                    val emojiKey = emojiKey(emoji) ?: return@mapNotNull null
                    reactionKey(messageId, emojiKey, if (pill.burst) 1 else 0)
                }.toSet()

                // Cache the actual Discord model before it disappears from the
                // message's reaction map. Its Emoji instance preserves Discord's
                // own renderable, src, display name and animation metadata.
                for (pill in rendered) {
                    if (pill.count <= 0 || pill.emoji == null) continue
                    val emojiKey = emojiKey(pill.emoji) ?: continue
                    val key = modelKey(channelId.orEmpty(), messageId, emojiKey, if (pill.burst) 1 else 0)
                    synchronized(reactionTemplates) {
                        reactionTemplates[key] = pill.reaction
                        while (reactionTemplates.size > 2_000) {
                            val first = reactionTemplates.keys.firstOrNull() ?: break
                            reactionTemplates.remove(first)
                        }
                    }
                }

                val markers = synchronized(removedUsersByKey) {
                    removedUsersByKey.keys.filter { it.startsWith("$messageId|") }.toList()
                }
                if (markers.isEmpty()) return@hook

                val expanded = ArrayList<Any>(incoming.size + markers.size)
                incoming.filterNotNullTo(expanded)
                var injected = 0
                for (markerKey in markers) {
                    if (markerKey in currentKeys) continue
                    val emojiAndType = markerKey.removePrefix("$messageId|")
                    val type = emojiAndType.substringAfterLast('|').toIntOrNull() ?: continue
                    val emojiKey = emojiAndType.substringBeforeLast('|')
                    val entry = synchronized(removalHistory) {
                        removalHistory.values
                            .filter {
                                it.messageId == messageId &&
                                    it.emojiKey == emojiKey &&
                                    it.reactionType == type &&
                                    (it.channelId.isBlank() || channelId == null || it.channelId == channelId)
                            }
                            .maxByOrNull { it.timestamp ?: 0L }
                    } ?: continue
                    val template = findReactionTemplate(channelId, messageId, emojiKey, type)
                    val synthetic = createZeroCountReaction(entry, template, reactionInterface, emojiClass)
                    if (synthetic != null) {
                        expanded.add(synthetic)
                        synchronized(syntheticReactionModels) { syntheticReactionModels[synthetic] = true }
                        injected++
                    }
                }

                if (injected > 0) {
                    param.args[0] = expanded
                    XposedBridge.log(
                        "Reaction Logs: injected $injected zero-count reaction model(s) into Discord setReactions",
                    )
                }
            },
            after = { param ->
                val messageId = bindingMessageId.get() ?: return@hook
                val flexbox = param.thisObject as ViewGroup
                val reactions = param.args.getOrNull(0) as? List<*> ?: emptyList<Any>()
                val reactionPills = pills(reactions, getCount, getBurstCount, getEmoji, isBurstReaction)

                for (index in 0 until flexbox.childCount) {
                    val pillView = flexbox.getChildAt(index) as? LinearLayout ?: continue
                    val pill = reactionPills.getOrNull(index)

                    if (pill == null || messageId == null || pill.emoji == null) {
                        boundPills.remove(pillView)
                        pillCounts.remove(pillView)
                        syntheticPillViews.remove(pillView)
                        restorePillStyle(pillView)
                        continue
                    }

                    val emoji = emojiKey(pill.emoji)
                    if (emoji.isNullOrBlank()) {
                        boundPills.remove(pillView)
                        pillCounts.remove(pillView)
                        syntheticPillViews.remove(pillView)
                        restorePillStyle(pillView)
                        continue
                    }

                    val key = reactionKey(messageId, emoji, if (pill.burst) 1 else 0)
                    val synthetic = synchronized(syntheticReactionModels) {
                        syntheticReactionModels.containsKey(pill.reaction)
                    }
                    boundPills[pillView] = key
                    pillCounts[pillView] = pill.count
                    if (synthetic) syntheticPillViews[pillView] = true
                    else syntheticPillViews.remove(pillView)

                    val removed = isRemoved(key)
                    applyPillStyle(pillView, removed, pill.count == 0)
                }
            },
        )
        XposedBridge.log("Reaction Logs: native reaction-pill model hooks installed")
    }

    private fun findReactionTemplate(
        channelId: String?,
        messageId: String,
        emojiKey: String,
        reactionType: Int,
    ): Any? = synchronized(reactionTemplates) {
        val exact = channelId?.let {
            reactionTemplates[modelKey(it, messageId, emojiKey, reactionType)]
        }
        exact ?: reactionTemplates.entries.firstOrNull { (key, _) ->
            key.messageId == messageId &&
                key.emojiKey == emojiKey &&
                key.reactionType == reactionType &&
                (channelId == null || key.channelId == channelId)
        }?.value
    }

    private fun createZeroCountReaction(
        entry: RemovalEntry,
        template: Any?,
        reactionInterface: Class<*>,
        emojiClass: Class<*>,
    ): Any? {
        val emoji = template?.let { readMember(it, "emoji") }
            ?: createEmojiModel(entry, emojiClass)
            ?: run {
                XposedBridge.log("Reaction Logs: cannot reconstruct emoji metadata for a removed reaction pill")
                return null
            }

        val proxy = Proxy.newProxyInstance(
            reactionInterface.classLoader ?: javaClass.classLoader,
            arrayOf(reactionInterface),
        ) { instance, method, args ->
            when (method.name) {
                "getCount", "getBurstCount" -> 0
                "getEmoji" -> emoji
                "isBurstReaction" -> entry.reactionType == 1
                "isMe", "isMeBurst", "getIsMe", "getIsMeBurst" -> false
                "getThemedBurstColors" -> template?.let { readMember(it, "themedBurstColors") }
                "getItemId" -> runCatching {
                    val defaults = reactionInterface.classLoader.loadClass(
                        "${reactionInterface.name}\$DefaultImpls",
                    )
                    defaults.methods.first {
                        it.name == "getItemId" && it.parameterCount == 1
                    }.invoke(null, instance)
                }.getOrNull() ?: 0L
                "toString" -> "ReactionLogsZeroCountReaction(${entry.emojiKey}, ${entry.reactionType})"
                "hashCode" -> System.identityHashCode(instance)
                "equals" -> instance === args?.firstOrNull()
                else -> if (template != null) {
                    runCatching { method.invoke(template, *(args ?: emptyArray())) }.getOrNull()
                } else {
                    null
                }
            }
        }
        return proxy
    }

    private fun createEmojiModel(entry: RemovalEntry, emojiClass: Class<*>): Any? = runCatching {
        val constructor = emojiClass.declaredConstructors.firstOrNull { candidate ->
            val types = candidate.parameterTypes
            types.size == 5 &&
                types[0] == String::class.java &&
                types[1] == String::class.java &&
                types[2] == String::class.java &&
                types[3] == String::class.java &&
                types[4] == java.lang.Boolean::class.java
        } ?: return@runCatching null
        val emojiId = entry.emojiId
            ?: entry.emojiKey.takeIf { it.startsWith("custom:") }?.removePrefix("custom:")
        val emojiName = entry.emojiName
            ?: entry.emojiKey.takeIf { it.startsWith("unicode:") }?.removePrefix("unicode:")
        val displayName = entry.emojiDisplayName ?: emojiName ?: "emoji"
        constructor.isAccessible = true
        constructor.newInstance(
            emojiName,
            entry.emojiSrc ?: "",
            displayName,
            emojiId,
            entry.emojiAnimated,
        )
    }.onFailure {
        XposedBridge.log("Reaction Logs: could not rebuild Discord emoji model: $it")
    }.getOrNull()

    private fun boundPillViews(): List<LinearLayout> = boundPills.keys.toList()
        .plus(syntheticPillViews.keys.toList())
        .distinct()

    /**
     * When a reaction's user list is open, mark the row belonging to a user whose
     * reaction-removal event was recorded for that message.
     */
    private fun hookReactionUserRows(dexFinder: DexFinder) {
        val widgetClass = dexFinder.classLoader.loadClass(MANAGE_REACTIONS_WIDGET)
        val configureUi = widgetClass.declaredMethods.firstOrNull {
            it.name == "configureUI" && it.parameterCount == 1
        } ?: error("WidgetManageReactions.configureUI(model) not found")
        configureUi.isAccessible = true
        configurePopupMethod = configureUi

        unhooks += configureUi.hook(before = { param ->
            val model = param.args.getOrNull(0) ?: return@hook
            val widget = param.thisObject
            lastPopupWidget = WeakReference(widget)
            lastPopupModel = model
            val augmented = augmentReactionPopup(widget, model, dexFinder.classLoader)
            if (augmented !== model) param.args[0] = augmented
        })

        // Stop retries and release popup references as soon as Discord destroys
        // the original Fragment view. This is UI cleanup only; saved history stays.
        val destroyPopupView = generateSequence(widgetClass) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { it.name == "onDestroyView" && it.parameterCount == 0 }
        if (destroyPopupView != null) {
            destroyPopupView.isAccessible = true
            unhooks += destroyPopupView.hook(before = { param ->
                if (lastPopupWidget?.get() === param.thisObject) {
                    lastPopupWidget = null
                    lastPopupModel = null
                    clearPopupState()
                }
            })
        } else {
            XposedBridge.log("Reaction Logs: WidgetManageReactions.onDestroyView not found; popup retry cleanup is limited")
        }

        val holderBase = dexFinder.classLoader.loadClass(MG_RECYCLER_VIEW_HOLDER)
        val configure = generateSequence(holderBase) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull {
                it.name == "onConfigure" &&
                    it.parameterCount == 2 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[1] == Any::class.java
            } ?: error("MGRecyclerViewHolder.onConfigure(int, Object) not found")
        configure.isAccessible = true

        unhooks += configure.hook(after = { param ->
            val messageId = detailsMessageId ?: return@hook
            val holder = param.thisObject
            val data = param.args.getOrNull(1) ?: return@hook
            val holderName = holder.javaClass.name
            val dataName = data.javaClass.name
            if (
                !holderName.contains("ManageReactionsResultsAdapter", ignoreCase = true) &&
                !dataName.contains("ReactionUserItem", ignoreCase = true)
            ) return@hook

            val itemView = holderItemView(holder) ?: return@hook
            val userId = extractUserId(data) ?: return@hook
            bindUserRow(itemView, messageId, userId)
        })

        XposedBridge.log("Reaction Logs: exact reaction-popup row hooks installed")
    }

    /**
     * Append the users removed from this exact message + selected emoji to the
     * existing Discord model. Constructing Discord's own ReactionUserItem keeps
     * its real User, avatar, display name and profile-click behavior.
     */
    private fun clearPopupState() {
        detailsGuildId = null
        detailsChannelId = null
        detailsMessageId = null
        detailsEmojiKey = null
        detailsReactionType = 0
        synchronized(popupRemovedUsers) { popupRemovedUsers.clear() }
        cancelPopupRetry()
        popupRetryKey = null
        refreshUserRows()
    }

    private fun clearPopupSelection(model: Any): Any {
        clearPopupState()
        return model
    }

    private fun augmentReactionPopup(widget: Any, model: Any, loader: ClassLoader): Any {
        val provider = readMember(widget, "modelProvider") ?: return clearPopupSelection(model)
        val channelId = readMember(provider, "channelId")?.toString() ?: return clearPopupSelection(model)
        val messageId = readMember(provider, "messageId")?.toString() ?: return clearPopupSelection(model)

        val reactionItems = readMember(model, "reactionItems") as? List<*> ?: return clearPopupSelection(model)
        val selectedItem = reactionItems.firstOrNull {
            readMember(it, "isSelected") == true
        } ?: return clearPopupSelection(model)
        val selectedReaction = readMember(selectedItem, "reaction") ?: return clearPopupSelection(model)
        val emoji = readMember(selectedReaction, "emoji") ?: return clearPopupSelection(model)
        val selectedEmojiKey = emojiKey(emoji) ?: return clearPopupSelection(model)
        val selectedReactionType = reactionType(selectedReaction, selectedItem)
        val channelLong = channelId.toLongOrNull() ?: return clearPopupSelection(model)
        val messageLong = messageId.toLongOrNull() ?: return clearPopupSelection(model)
        val guildId = resolveGuildId(loader, channelLong)

        detailsGuildId = guildId
        detailsChannelId = channelId
        detailsMessageId = messageId
        detailsEmojiKey = selectedEmojiKey
        detailsReactionType = selectedReactionType

        val originalItems = readMember(model, "userItems") as? List<*> ?: return clearPopupSelection(model)

        val activeForSelection = synchronized(activeReactions) {
            activeReactions.filter { active ->
                active.channelId == channelId &&
                    active.messageId == messageId &&
                    active.emojiKey == selectedEmojiKey &&
                    active.reactionType == selectedReactionType &&
                    (active.guildId.isNullOrBlank() || guildId.isNullOrBlank() || active.guildId == guildId)
            }.toList()
        }
        val selectionRetryKey = userRowKey(guildId, channelId, messageId, selectedEmojiKey, selectedReactionType, "")
        if (popupRetryKey != selectionRetryKey) {
            cancelPopupRetry()
            popupRetryKey = selectionRetryKey
        }

        val historyForSelection = synchronized(removalHistory) {
            removalHistory.values.filter { entry ->
                entry.messageId == messageId &&
                    (entry.channelId.isBlank() || entry.channelId == channelId) &&
                    entry.emojiKey == selectedEmojiKey &&
                    entry.reactionType == selectedReactionType &&
                    (entry.guildId.isNullOrBlank() || guildId.isNullOrBlank() || entry.guildId == guildId)
            }.sortedBy { it.timestamp ?: 0L }
        }

        val currentActiveIds = activeForSelection.mapTo(LinkedHashSet()) { it.userId }
        val existingUsers = originalItems.mapNotNull(::extractUserId).toMutableSet()
        val output = ArrayList<Any>(originalItems.size + 8)
        originalItems.filterNotNullTo(output)

        for (active in activeForSelection) {
            if (!existingUsers.add(active.userId)) continue
            val user = resolveUser(loader, active.userId) ?: continue
            val member = guildId?.let { resolveGuildMember(loader, it, active.userId) }
            createReactionUserItem(loader, user, channelLong, messageLong, emoji, member)?.let {
                output.add(it)
            }
        }

        val removedIds = LinkedHashSet<String>()
        var unresolvedUsers = false
        for (entry in historyForSelection) {
            if (entry.userId in currentActiveIds) continue
            // Discord can dispatch the removal before its popup model refreshes.
            // Briefly mark that stale row red, then re-check the model. For older
            // history, a user already present in Discord's active list is authoritative
            // evidence that the reaction is active again (including while the plugin
            // was unloaded and could not observe the ADD event).
            if (entry.userId in existingUsers) {
                // The original Discord userItems list is authoritative. If the
                // user is still returned by Discord, do not re-add or paint an
                // old history record red after they have reacted again.
                continue
            }
            val user = resolveUser(loader, entry.userId)
            if (user == null) {
                unresolvedUsers = true
                continue
            }
            val member = guildId?.let { resolveGuildMember(loader, it, entry.userId) }
            val row = createReactionUserItem(loader, user, channelLong, messageLong, emoji, member)
            if (row == null) {
                unresolvedUsers = true
                continue
            }
            output.add(row)
            existingUsers.add(entry.userId)
            removedIds.add(entry.userId)
        }

        synchronized(popupRemovedUsers) {
            popupRemovedUsers.clear()
            removedIds.forEach { userId ->
                popupRemovedUsers.add(userRowKey(guildId, channelId, messageId, selectedEmojiKey, selectedReactionType, userId))
            }
        }
        refreshUserRows()
        if (unresolvedUsers) schedulePopupRetry(selectionRetryKey) else cancelPopupRetry()

        if (output == originalItems) return model
        if (removedIds.isNotEmpty()) {
            XposedBridge.log("Reaction Logs: appended ${removedIds.size} removed user row(s) to Discord's original popup")
        }
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

    private fun snowflakeId(value: Any?, depth: Int = 0): String? {
        if (value == null || depth > 2) return null
        when (value) {
            is String -> return value.trim().takeIf { it.isNotEmpty() && it != "null" }
            is Number -> return value.toLong().toString()
        }

        val boxed = readMember(value, "value")
            ?: runCatching {
                value.javaClass.methods.firstOrNull {
                    it.name == "unbox-impl" && it.parameterCount == 0
                }?.invoke(value)
            }.getOrNull()
        if (boxed != null && boxed !== value) snowflakeId(boxed, depth + 1)?.let { return it }

        val rendered = value.toString().trim()
        return rendered.takeIf {
            it.isNotEmpty() && it != "null" && it.matches(Regex("\\d+"))
        }
    }

    private fun readMember(target: Any?, name: String): Any? {
        if (target == null) return null
        val suffix = name.replaceFirstChar { it.uppercase() }
        val method = target.javaClass.methods.firstOrNull {
            it.parameterCount == 0 && it.name in setOf(name, "get$suffix", "is$suffix")
        }
        if (method != null) {
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

    private fun resolveStore(loader: ClassLoader, getter: String): Any? = runCatching {
        val stream = loader.loadClass("com.discord.stores.StoreStream")
        val method = stream.methods.firstOrNull {
            Modifier.isStatic(it.modifiers) && it.name == getter && it.parameterCount == 0
        } ?: return@runCatching null
        method.invoke(null)
    }.getOrNull()

    private fun resolveUser(loader: ClassLoader, userId: String): Any? {
        val numericId = userId.toLongOrNull() ?: return null
        val store = resolveStore(loader, "getUsers") ?: return null
        val users = readMember(store, "users") as? Map<*, *>
            ?: runCatching {
                store.javaClass.methods.firstOrNull {
                    it.name == "getUsers" && it.parameterCount == 0
                }?.invoke(store) as? Map<*, *>
            }.getOrNull()
        users?.get(numericId)?.let { return it }
        users?.get(userId)?.let { return it }

        return runCatching {
            store.javaClass.methods.firstOrNull {
                it.name == "getUser" && it.parameterCount == 1
            }?.invoke(store, numericId)
        }.getOrNull()
    }

    private fun resolveGuildId(loader: ClassLoader, channelId: Long): String? = runCatching {
        val store = resolveStore(loader, "getChannels") ?: return@runCatching null
        val channel = store.javaClass.methods.firstOrNull {
            it.name == "getChannel" && it.parameterCount == 1
        }?.invoke(store, channelId) ?: return@runCatching null
        readMember(channel, "guildId")?.toString()?.takeIf { it != "0" && it != "null" }
    }.getOrNull()

    private fun resolveGuildMember(loader: ClassLoader, guildId: String, userId: String): Any? = runCatching {
        val guilds = resolveStore(loader, "getGuilds") ?: return@runCatching null
        val members = guilds.javaClass.methods.firstOrNull {
            it.name == "getMembers" && it.parameterCount == 0
        }?.invoke(guilds) as? Map<*, *> ?: return@runCatching null
        val guildMembers = members[guildId.toLongOrNull()] as? Map<*, *>
            ?: members[guildId] as? Map<*, *>
            ?: return@runCatching null
        val numericUserId = userId.toLongOrNull()
        guildMembers[numericUserId] ?: guildMembers[userId]
    }.getOrNull()

    private fun createReactionUserItem(
        loader: ClassLoader,
        user: Any,
        channelId: Long,
        messageId: Long,
        emoji: Any,
        guildMember: Any?,
    ): Any? = runCatching {
        val itemClass = loader.loadClass(REACTION_USER_ITEM)
        val constructor = itemClass.declaredConstructors.firstOrNull { candidate ->
            val types = candidate.parameterTypes
            types.size == 6 &&
                types[0].name == "com.discord.models.user.User" &&
                types[0].isAssignableFrom(user.javaClass) &&
                types[1] == Long::class.javaPrimitiveType &&
                types[2] == Long::class.javaPrimitiveType &&
                types[3].name == "com.discord.api.message.reaction.MessageReactionEmoji" &&
                types[3].isAssignableFrom(emoji.javaClass) &&
                types[4] == Boolean::class.javaPrimitiveType &&
                types[5].name == "com.discord.models.member.GuildMember" &&
                (guildMember == null || types[5].isAssignableFrom(guildMember.javaClass))
        } ?: return@runCatching null
        constructor.isAccessible = true
        constructor.newInstance(user, channelId, messageId, emoji, false, guildMember)
    }.onFailure {
        XposedBridge.log("Reaction Logs: could not create removed-user popup row: $it")
    }.getOrNull()

    private fun bindUserRow(view: View, messageId: String, userId: String) {
        val guildId = detailsGuildId
        val channelId = detailsChannelId ?: return
        val emojiKey = detailsEmojiKey ?: return
        if (detailsMessageId != messageId) return
        val reactionType = detailsReactionType
        // RecyclerView reuses holders between emoji tabs; restore the previous row's
        // original styling before assigning this holder a different reaction identity.
        applyUserRowStyle(view, false)
        val binding = UserRowBinding(guildId, channelId, messageId, emojiKey, reactionType, userId)
        userRows[view] = binding
        mainHandler.post {
            if (userRows[view] != binding) return@post
            val highlighted = synchronized(popupRemovedUsers) {
                userRowKey(binding.guildId, binding.channelId, binding.messageId, binding.emojiKey, binding.reactionType, binding.userId) in popupRemovedUsers
            }
            applyUserRowStyle(view, highlighted)
        }
    }

    private fun refreshUserRows() {
        for ((view, binding) in userRows.entries.toList()) {
            val key = userRowKey(binding.guildId, binding.channelId, binding.messageId, binding.emojiKey, binding.reactionType, binding.userId)
            val highlighted = synchronized(popupRemovedUsers) { key in popupRemovedUsers }
            applyUserRowStyle(view, highlighted)
        }
    }

    private fun schedulePopupRetry(key: String) {
        if (lastPopupWidget?.get() == null || lastPopupModel == null) return
        if (popupRetryKey != key) {
            cancelPopupRetry()
            popupRetryKey = key
        }
        // A retry is valid only for the same open popup and the same selected
        // message/emoji/type. onDestroyView clears the widget reference.
        if (popupRetryAttempts >= 8 || popupRetryRunnable != null) return
        val delay = (250L shl popupRetryAttempts).coerceAtMost(8_000L)
        val retry = Runnable {
            popupRetryRunnable = null
            if (popupRetryKey != key || lastPopupWidget?.get() == null || lastPopupModel == null) {
                cancelPopupRetry()
                return@Runnable
            }
            popupRetryAttempts++
            refreshPopupModel()
        }
        popupRetryRunnable = retry
        mainHandler.postDelayed(retry, delay)
    }

    private fun cancelPopupRetry() {
        popupRetryRunnable?.let(mainHandler::removeCallbacks)
        popupRetryRunnable = null
        popupRetryAttempts = 0
    }

    private fun reactionType(reaction: Any, selectedItem: Any): Int {
        val direct = readMember(reaction, "reactionType")
            ?: readMember(reaction, "selectedReactionType")
            ?: readMember(reaction, "type")
            ?: readMember(selectedItem, "reactionType")
            ?: readMember(selectedItem, "selectedReactionType")
            ?: readMember(selectedItem, "type")
        if (direct is Number) return direct.toInt()
        if (direct is String) {
            direct.toIntOrNull()?.let { return it }
            if (direct.equals("burst", ignoreCase = true)) return 1
            if (direct.equals("normal", ignoreCase = true)) return 0
        }

        val burstFlag = readMember(reaction, "isBurstReaction")
            ?: readMember(reaction, "isBurst")
            ?: readMember(reaction, "burst")
            ?: readMember(selectedItem, "isBurstReaction")
            ?: readMember(selectedItem, "isBurst")
            ?: readMember(selectedItem, "burst")
        if (burstFlag is Boolean) return if (burstFlag) 1 else 0

        // The current MessageReaction model can carry both normal and burst
        // counts. Use an exclusive count when only one flavour exists.
        val normalCount = (readMember(reaction, "count") as? Number)?.toInt() ?: 0
        val burstCount = (readMember(reaction, "burstCount") as? Number)?.toInt() ?: 0
        return if (burstCount > 0 && normalCount == 0) 1 else 0
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
            if (child !is TextView) return@visitViews
            if (highlighted) {
                if (!originalUserTextColors.containsKey(child)) {
                    originalUserTextColors[child] = child.textColors
                }
                child.setTextColor(REMOVED_RED)
            } else if (originalUserTextColors.containsKey(child)) {
                child.setTextColor(originalUserTextColors.remove(child))
            }
        }
    }

    private fun visitViews(view: View, action: (View) -> Unit) {
        action(view)
        if (view !is ViewGroup) return
        for (index in 0 until view.childCount) {
            visitViews(view.getChildAt(index), action)
        }
    }

    private fun extractUserId(value: Any?, depth: Int = 0): String? {
        if (value == null || depth > 3) return null
        val type = value.javaClass
        val typeName = type.name.lowercase()

        val userIdMethod = type.methods.firstOrNull {
            it.name in setOf("getUserId", "getUserID") && it.parameterCount == 0
        }
        val directId = runCatching { userIdMethod?.invoke(value)?.toString() }.getOrNull()
        if (!directId.isNullOrBlank() && directId != "null") return directId

        if (typeName.contains("user") || typeName.contains("member")) {
            val idMethod = type.methods.firstOrNull {
                it.name == "getId" && it.parameterCount == 0
            }
            val id = runCatching { idMethod?.invoke(value)?.toString() }.getOrNull()
            if (!id.isNullOrBlank() && id != "null") return id
        }

        val nestedGetters = type.methods.filter {
            it.parameterCount == 0 && it.name in setOf("getUser", "getMember", "getUserModel")
        }
        for (getter in nestedGetters) {
            val nested = runCatching { getter.invoke(value) }.getOrNull()
            extractUserId(nested, depth + 1)?.let { return it }
        }

        var current: Class<*>? = type
        while (current != null) {
            for (field in current.declaredFields) {
                if (Modifier.isStatic(field.modifiers)) continue
                val fieldName = field.name.lowercase()
                if (!fieldName.contains("user") && !fieldName.contains("member")) continue
                val nested = runCatching {
                    field.isAccessible = true
                    field.get(value)
                }.getOrNull()
                if (nested is String && nested.isNotBlank() && fieldName.contains("userid")) {
                    return nested
                }
                extractUserId(nested, depth + 1)?.let { return it }
            }
            current = current.superclass
        }

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

    private fun applyPillStyle(view: LinearLayout, removed: Boolean, zeroCount: Boolean) {
        if (!removed) {
            restorePillStyle(view)
            return
        }

        originalPillStyles.getOrPut(view) {
            val count = countText(view)
            val switcher = countSwitcher(view)
            val labels = LinkedHashMap<Int, Int>()
            reactionLabelViews(view).forEach { label ->
                if (label.id != View.NO_ID) labels[label.id] = label.visibility
            }
            OriginalPillStyle(
                backgroundTint = view.backgroundTintList,
                countColors = count?.textColors,
                countText = count?.text,
                countVisibility = count?.visibility,
                countSwitcherVisibility = switcher?.visibility,
                labelVisibilities = labels,
            )
        }

        view.backgroundTintList = ColorStateList.valueOf(REMOVED_TINT)
        countText(view)?.setTextColor(REMOVED_RED)
        if (zeroCount) setPillCountZero(view)
    }

    private fun restorePillStyle(view: LinearLayout) {
        val original = originalPillStyles.remove(view) ?: return
        view.backgroundTintList = original.backgroundTint
        val count = countText(view)
        original.countColors?.let { count?.setTextColor(it) }
        original.countText?.let { count?.text = it }
        original.countVisibility?.let { count?.visibility = it }
        original.countSwitcherVisibility?.let { countSwitcher(view)?.visibility = it }
        if (original.labelVisibilities.isNotEmpty()) {
            visitViews(view) { child ->
                if (child.id != View.NO_ID && original.labelVisibilities.containsKey(child.id)) {
                    child.visibility = original.labelVisibilities.getValue(child.id)
                }
            }
        }
    }

    private fun countSwitcher(view: ViewGroup): TextSwitcher? {
        for (index in 0 until view.childCount) {
            val child = view.getChildAt(index)
            if (child is TextSwitcher) return child
        }
        return null
    }

    private fun countText(view: ViewGroup): TextView? = countSwitcher(view)?.currentView as? TextView

    private fun isReactionLabel(view: View): Boolean {
        if (view.id == View.NO_ID) return false
        val name = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull() ?: return false
        return name.contains("reaction_label", ignoreCase = true)
    }

    private fun reactionLabelViews(view: LinearLayout): List<View> {
        // The actual Discord ReactionView owns these views through its generated
        // ReactionViewBinding. Prefer those typed binding members; use resource
        // names only as a compatibility fallback for a future layout revision.
        val binding = readMember(view, "binding")
        val bound = listOf("reactionLabelLeft", "reactionLabelRight")
            .mapNotNull { readMember(binding, it) as? View }
        if (bound.isNotEmpty()) return bound.distinct()

        val fallback = ArrayList<View>(2)
        visitViews(view) { child -> if (isReactionLabel(child)) fallback.add(child) }
        return fallback
    }

    private fun setPillCountZero(view: LinearLayout) {
        val switcher = countSwitcher(view) ?: return
        reactionLabelViews(view).forEach { it.visibility = View.GONE }
        switcher.visibility = View.VISIBLE
        // TextSwitcher.setCurrentText also updates its current child correctly;
        // setting currentView.text alone can leave a stale outgoing child visible.
        switcher.setCurrentText("0")
        (switcher.currentView as? TextView)?.visibility = View.VISIBLE
    }

    private fun pills(
        reactions: List<*>,
        getCount: Method,
        getBurstCount: Method,
        getEmoji: Method,
        isBurstReaction: Method,
    ): List<Pill> = reactions.flatMap { reaction ->
        if (reaction == null) return@flatMap emptyList()
        val count = getCount.invoke(reaction) as Int
        val burstCount = getBurstCount.invoke(reaction) as Int
        val emoji = getEmoji.invoke(reaction)
        val modelIsBurst = runCatching { isBurstReaction.invoke(reaction) as Boolean }
            .getOrDefault(burstCount > 0)
        when {
            burstCount > 0 && count > 0 -> listOf(
                Pill(reaction, emoji, true, burstCount),
                Pill(reaction, emoji, false, count),
            )
            burstCount > 0 -> listOf(Pill(reaction, emoji, true, burstCount))
            count > 0 -> listOf(Pill(reaction, emoji, false, count))
            else -> listOf(Pill(reaction, emoji, modelIsBurst, 0))
        }
    }

    private fun emojiKey(emoji: Any): String? {
        val methods = emoji.javaClass.methods
        val id = runCatching {
            methods.firstOrNull { it.name == "getId" && it.parameterCount == 0 }
                ?.invoke(emoji)?.toString()?.takeIf { it.isNotBlank() && it != "null" }
        }.getOrNull()
        val name = runCatching {
            methods.firstOrNull { it.name == "getName" && it.parameterCount == 0 }
                ?.invoke(emoji)?.toString()?.takeIf { it.isNotBlank() && it != "null" }
        }.getOrNull()

        if (id != null) return "custom:$id"
        if (name != null) return "unicode:${Normalizer.normalize(name, Normalizer.Form.NFC)}"

        val text = emoji.toString()
        val parsedId = ID_PATTERN.find(text)?.groupValues?.get(1)
            ?.takeIf { it.isNotBlank() && it != "null" }
        val parsedName = NAME_PATTERN.find(text)?.groupValues?.get(1)
            ?.takeIf { it.isNotBlank() && it != "null" }
        return when {
            parsedId != null -> "custom:$parsedId"
            parsedName != null -> "unicode:${Normalizer.normalize(parsedName, Normalizer.Form.NFC)}"
            else -> null
        }
    }

    private companion object {
        val ID_PATTERN = Regex("""(?:[(]|, )id=([^,)]*)""")
        val NAME_PATTERN = Regex("""(?:[(]|, )name=([^,)]*)""")
    }
}

private fun reactionKey(messageId: String, emojiKey: String, type: Int) =
    "$messageId|$emojiKey|$type"

private fun modelKey(channelId: String, messageId: String, emojiKey: String, type: Int) =
    ReactionModelKey(channelId, messageId, emojiKey, type)

private fun userRowKey(guildId: String?, channelId: String, messageId: String, emojiKey: String, type: Int, userId: String) =
    listOf(guildId.orEmpty(), channelId, messageId, emojiKey, type.toString(), userId).joinToString("\u0000")

private inline fun Method.hook(
    crossinline before: (XC_MethodHook.MethodHookParam) -> Unit = {},
    crossinline after: (XC_MethodHook.MethodHookParam) -> Unit = {},
): XC_MethodHook.Unhook = XposedBridge.hookMethod(this, object : XC_MethodHook() {
    override fun beforeHookedMethod(param: MethodHookParam) = before(param)
    override fun afterHookedMethod(param: MethodHookParam) = after(param)
})
