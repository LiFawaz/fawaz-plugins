import type {
    ActiveReaction,
    PopupReactionContext,
    ReactionIdentity,
    ReactionPayload,
    ReactionRuntimeState,
    ReactionStorage,
    RemovalRecord,
} from './shared'

export const REMOVAL_RETENTION_MS = 30 * 24 * 60 * 60 * 1000
export const MAX_REMOVAL_RECORDS = 1000
export const MAX_ACTIVE_REACTIONS = 5000
export const DUPLICATE_REMOVE_WINDOW_MS = 5000

const nonEmpty = (value: unknown): string | null => {
    if (typeof value !== 'string') return null
    const result = value.trim()
    return result.length > 0 && result !== 'null' ? result : null
}

const eventString = (
    payload: ReactionPayload,
    camel: keyof ReactionPayload,
    snake: keyof ReactionPayload,
): string | null => nonEmpty(payload[camel]) ?? nonEmpty(payload[snake])

const numericType = (payload: ReactionPayload): number => {
    const value = payload.reactionType ?? payload.reaction_type ?? payload.type
    if (typeof value === 'number' && Number.isFinite(value)) return Math.trunc(value)
    if (typeof value === 'string' && /^\d+$/.test(value)) return Number(value)
    return payload.burst === true ? 1 : 0
}

export function emojiIdentity(emoji: ReactionPayload['emoji']): {
    key: string
    id: string | null
    name: string | null
} | null {
    if (!emoji) return null
    const id = nonEmpty(emoji.id)
    const name = nonEmpty(emoji.name)
    if (id) return { key: `custom:${id}`, id, name }
    if (!name) return null
    const normalized = name.normalize('NFC')
    return { key: `unicode:${normalized}`, id: null, name: normalized }
}

export function reactionIdentity(payload: ReactionPayload): ReactionIdentity | null {
    const channelId = eventString(payload, 'channelId', 'channel_id')
    const messageId = eventString(payload, 'messageId', 'message_id')
    const userId = eventString(payload, 'userId', 'user_id')
    const emoji = emojiIdentity(payload.emoji)
    if (!channelId || !messageId || !userId || !emoji) return null

    const guildId = nonEmpty(payload.guildId) ?? nonEmpty(payload.guild_id)
    const reactionType = numericType(payload)
    const identityKey = JSON.stringify([
        guildId ?? '',
        channelId,
        messageId,
        emoji.key,
        reactionType,
        userId,
    ])

    return {
        identityKey,
        guildId,
        channelId,
        messageId,
        userId,
        emojiId: emoji.id,
        emojiName: emoji.name,
        emojiKey: emoji.key,
        reactionType,
    }
}

export function reactionPillKey(value: Pick<ReactionIdentity, 'messageId' | 'emojiKey' | 'reactionType'>): string {
    return `${value.messageId}|${value.emojiKey}|${value.reactionType}`
}

function legacyEmojiIdentity(value: string): { key: string; id: string | null; name: string | null } | null {
    if (value.startsWith('custom:')) {
        const id = nonEmpty(value.slice('custom:'.length))
        return id ? { key: `custom:${id}`, id, name: null } : null
    }
    if (value.startsWith('unicode:')) {
        const name = nonEmpty(value.slice('unicode:'.length))
        return name ? { key: `unicode:${name.normalize('NFC')}`, id: null, name: name.normalize('NFC') } : null
    }

    // 1.2.9 used "name:id" for custom emoji and the raw Unicode value otherwise.
    const customId = /:(\d+)$/.exec(value)?.[1]
    if (customId) return { key: `custom:${customId}`, id: customId, name: value.slice(0, -(customId.length + 1)) || null }
    const name = nonEmpty(value)
    return name ? { key: `unicode:${name.normalize('NFC')}`, id: null, name: name.normalize('NFC') } : null
}

function migrateLegacyRemovals(
    entries: unknown,
    records: Map<string, RemovalRecord>,
    now: number,
): void {
    if (!Array.isArray(entries)) return
    for (const raw of entries) {
        if (!raw || typeof raw !== 'object') continue
        const item = raw as { pillKey?: unknown; removedUsers?: unknown }
        const pillKey = nonEmpty(item.pillKey)
        if (!pillKey || !Array.isArray(item.removedUsers)) continue

        const parts = pillKey.split('|')
        const messageId = parts.shift()
        const typeText = parts.pop()
        const emojiText = parts.join('|')
        const reactionType = Number(typeText)
        const emoji = legacyEmojiIdentity(emojiText)
        if (!messageId || !emoji || !Number.isFinite(reactionType)) continue

        for (const rawUser of item.removedUsers) {
            const userId = typeof rawUser === 'string'
                ? nonEmpty(rawUser)
                : rawUser && typeof rawUser === 'object'
                    ? nonEmpty((rawUser as { id?: unknown }).id)
                    : null
            // The old "*" marker did not identify a person, so never fabricate a profile.
            if (!userId || userId === '*') continue

            const identityKey = JSON.stringify([
                '',
                '',
                messageId,
                emoji.key,
                Math.trunc(reactionType),
                userId,
            ])
            const id = `legacy:${identityKey}`
            if (records.has(id)) continue
            records.set(id, {
                id,
                identityKey,
                guildId: null,
                channelId: '',
                messageId,
                userId,
                emojiId: emoji.id,
                emojiName: emoji.name,
                emojiKey: emoji.key,
                reactionType: Math.trunc(reactionType),
                timestamp: now,
                epoch: 0,
            })
        }
    }
}

export function normalizeStorage(
    input: Partial<ReactionStorage> | null | undefined,
    now = Date.now(),
): ReactionStorage {
    const records = new Map<string, RemovalRecord>()
    if (Array.isArray(input?.removalHistory)) {
        for (const raw of input.removalHistory) {
            if (!raw || typeof raw !== 'object') continue
            const item = raw as RemovalRecord
            if (
                typeof item.id !== 'string' ||
                typeof item.identityKey !== 'string' ||
                typeof item.channelId !== 'string' ||
                typeof item.messageId !== 'string' ||
                typeof item.userId !== 'string' ||
                typeof item.emojiKey !== 'string' ||
                !item.id ||
                !item.identityKey ||
                !item.messageId ||
                !item.userId ||
                !item.emojiKey
            ) continue

            const timestamp = Number.isFinite(item.timestamp) && item.timestamp > 0
                ? item.timestamp
                : now
            records.set(item.id, {
                ...item,
                guildId: nonEmpty(item.guildId),
                emojiId: nonEmpty(item.emojiId),
                emojiName: nonEmpty(item.emojiName),
                reactionType: Number.isFinite(item.reactionType) ? Math.trunc(item.reactionType) : 0,
                timestamp,
                epoch: Number.isFinite(item.epoch) && item.epoch >= 0 ? Math.trunc(item.epoch) : 0,
            })
        }
    }

    migrateLegacyRemovals(input?.activeRemovals, records, now)

    const cutoff = now - REMOVAL_RETENTION_MS
    const removalHistory = [...records.values()]
        .filter(item => item.timestamp >= cutoff)
        .sort((a, b) => a.timestamp - b.timestamp)
        .slice(-MAX_REMOVAL_RECORDS)

    const epochs: Record<string, number> = {}
    if (input?.epochs && typeof input.epochs === 'object') {
        for (const [key, value] of Object.entries(input.epochs)) {
            if (typeof value === 'number' && Number.isFinite(value) && value >= 0) {
                epochs[key] = Math.trunc(value)
            }
        }
    }
    for (const item of removalHistory) {
        if (item.id.startsWith('legacy:')) continue
        epochs[item.identityKey] = Math.max(epochs[item.identityKey] ?? 0, item.epoch)
    }

    const liveKeys = new Set(removalHistory.map(item => item.identityKey))
    for (const key of Object.keys(epochs)) {
        if (!liveKeys.has(key)) delete epochs[key]
    }

    return {
        schemaVersion: 2,
        removalHistory,
        epochs,
    }
}

function normalizeActiveReactions(input: ActiveReaction[]): ActiveReaction[] {
    const byKey = new Map<string, ActiveReaction>()
    for (const item of input) {
        if (!item || !item.identityKey || !item.channelId || !item.messageId || !item.userId || !item.emojiKey) continue
        byKey.set(item.identityKey, item)
    }
    return [...byKey.values()].slice(-MAX_ACTIVE_REACTIONS)
}

export function hydrateState(
    cache: Partial<ReactionStorage> | null | undefined,
    now = Date.now(),
): ReactionRuntimeState {
    return {
        storage: normalizeStorage(cache, now),
        // Discord's current reactor store is authoritative after a plugin reload.
        activeReactions: [],
    }
}

export function applyReactionAdd(
    input: ReactionRuntimeState,
    payload: ReactionPayload,
    now = Date.now(),
): ReactionRuntimeState {
    const storage = normalizeStorage(input.storage, now)
    const identity = reactionIdentity(payload)
    if (!identity) return { storage, activeReactions: normalizeActiveReactions(input.activeReactions) }

    const active = new Map(input.activeReactions.map(item => [item.identityKey, item]))
    if (!active.has(identity.identityKey)) {
        active.set(identity.identityKey, identity)
        storage.epochs[identity.identityKey] = (storage.epochs[identity.identityKey] ?? 0) + 1
    }

    return {
        storage,
        activeReactions: normalizeActiveReactions([...active.values()]),
    }
}

export function applyReactionRemove(
    input: ReactionRuntimeState,
    payload: ReactionPayload,
    now = Date.now(),
): ReactionRuntimeState {
    const storage = normalizeStorage(input.storage, now)
    const identity = reactionIdentity(payload)
    if (!identity) return { storage, activeReactions: normalizeActiveReactions(input.activeReactions) }

    const active = new Map(input.activeReactions.map(item => [item.identityKey, item]))
    const wasActive = active.delete(identity.identityKey)
    const epoch = storage.epochs[identity.identityKey] ?? 0
    let id = JSON.stringify([identity.identityKey, epoch])
    const previous = storage.removalHistory.find(item => item.id === id)

    // Optimistic/gateway echoes for the same remove event are idempotent. When a
    // prior record is old and this identity was not seen active after plugin reload,
    // advance its epoch so a genuine new removal is not lost.
    if (previous && !wasActive) {
        if (now - previous.timestamp <= DUPLICATE_REMOVE_WINDOW_MS) {
            return { storage, activeReactions: normalizeActiveReactions([...active.values()]) }
        }
        storage.epochs[identity.identityKey] = epoch + 1
        id = JSON.stringify([identity.identityKey, epoch + 1])
    }

    if (!storage.removalHistory.some(item => item.id === id)) {
        const record: RemovalRecord = {
            ...identity,
            id,
            timestamp: now,
            epoch: id === JSON.stringify([identity.identityKey, epoch]) ? epoch : epoch + 1,
        }
        storage.removalHistory.push(record)
    }

    return {
        storage: normalizeStorage(storage, now),
        activeReactions: normalizeActiveReactions([...active.values()]),
    }
}

export function applyRemoveAll(
    input: ReactionRuntimeState,
    payload: ReactionPayload,
    now = Date.now(),
): ReactionRuntimeState {
    const storage = normalizeStorage(input.storage, now)
    const channelId = eventString(payload, 'channelId', 'channel_id')
    const messageId = eventString(payload, 'messageId', 'message_id')
    if (!channelId || !messageId) return { storage, activeReactions: normalizeActiveReactions(input.activeReactions) }

    // Discord does not identify individual affected users in the bulk event.
    return {
        storage,
        activeReactions: input.activeReactions.filter(
            item => item.channelId !== channelId || item.messageId !== messageId,
        ),
    }
}

export function applyRemoveEmoji(
    input: ReactionRuntimeState,
    payload: ReactionPayload,
    now = Date.now(),
): ReactionRuntimeState {
    const storage = normalizeStorage(input.storage, now)
    const channelId = eventString(payload, 'channelId', 'channel_id')
    const messageId = eventString(payload, 'messageId', 'message_id')
    const emoji = emojiIdentity(payload.emoji)
    if (!channelId || !messageId || !emoji) return { storage, activeReactions: normalizeActiveReactions(input.activeReactions) }

    // This bulk event also carries no per-user identity; preserve history unchanged.
    return {
        storage,
        activeReactions: input.activeReactions.filter(
            item =>
                item.channelId !== channelId ||
                item.messageId !== messageId ||
                item.emojiKey !== emoji.key,
        ),
    }
}

export function applyMessageDelete(
    input: ReactionRuntimeState,
    payload: ReactionPayload,
    now = Date.now(),
): ReactionRuntimeState {
    const messageId = nonEmpty(payload.id) ?? eventString(payload, 'messageId', 'message_id')
    const channelId = eventString(payload, 'channelId', 'channel_id')
    const storage = normalizeStorage(input.storage, now)
    if (!messageId) return { storage, activeReactions: normalizeActiveReactions(input.activeReactions) }

    const removalHistory = storage.removalHistory.filter(
        item => item.messageId !== messageId ||
            (channelId != null && item.channelId.length > 0 && item.channelId !== channelId),
    )
    const activeReactions = input.activeReactions.filter(
        item => item.messageId !== messageId || (channelId != null && item.channelId !== channelId),
    )
    const liveKeys = new Set([
        ...removalHistory.map(item => item.identityKey),
        ...activeReactions.map(item => item.identityKey),
    ])
    const epochs = { ...storage.epochs }
    for (const key of Object.keys(epochs)) {
        if (!liveKeys.has(key)) delete epochs[key]
    }

    return {
        storage: { schemaVersion: 2, removalHistory, epochs },
        activeReactions: normalizeActiveReactions(activeReactions),
    }
}

export function applyBulkMessageDelete(
    input: ReactionRuntimeState,
    payload: ReactionPayload,
    now = Date.now(),
): ReactionRuntimeState {
    let current = input
    const ids = Array.isArray(payload.ids)
        ? payload.ids.map(nonEmpty).filter((value): value is string => value != null)
        : []
    for (const id of ids) {
        current = applyMessageDelete(
            current,
            { id, channelId: payload.channelId, channel_id: payload.channel_id },
            now,
        )
    }
    return current
}

function scopeMatches(record: RemovalRecord, context: PopupReactionContext): boolean {
    return record.messageId === context.messageId &&
        (record.channelId.length === 0 || record.channelId === context.channelId) &&
        record.emojiKey === context.emojiKey &&
        (!record.guildId || !context.guildId || record.guildId === context.guildId)
}

export function removedUsersForPopup(
    state: ReactionRuntimeState,
    context: PopupReactionContext,
    currentUserIds: Iterable<string> = [],
): string[] {
    const current = new Set(currentUserIds)
    const active = new Set(
        state.activeReactions
            .filter(item =>
                item.channelId === context.channelId &&
                item.messageId === context.messageId &&
                item.emojiKey === context.emojiKey &&
                (!item.guildId || !context.guildId || item.guildId === context.guildId),
            )
            .map(item => item.userId),
    )

    const removed = new Set<string>()
    for (const item of state.storage.removalHistory) {
        if (!scopeMatches(item, context) || current.has(item.userId) || active.has(item.userId)) continue
        removed.add(item.userId)
    }
    return [...removed]
}

export function buildNativeSnapshot(state: ReactionRuntimeState, now = Date.now()) {
    const storage = normalizeStorage(state.storage, now)
    const activeReactions = normalizeActiveReactions(state.activeReactions)
    const activeKeys = new Set(activeReactions.map(item => item.identityKey))
    const grouped = new Map<string, Set<string>>()

    for (const item of storage.removalHistory) {
        if (activeKeys.has(item.identityKey)) continue
        const pillKey = reactionPillKey(item)
        const users = grouped.get(pillKey) ?? new Set<string>()
        users.add(item.userId)
        grouped.set(pillKey, users)
    }

    return {
        schemaVersion: 2,
        activeRemovals: [...grouped].map(([pillKey, users]) => ({
            pillKey,
            removedUsers: [...users].map(id => ({ id })),
        })),
        removalHistory: storage.removalHistory,
        activeReactions,
    }
}
