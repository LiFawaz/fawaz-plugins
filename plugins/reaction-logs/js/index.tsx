import { onFluxEventDispatched } from '@valenced/discord/flux'
import { setRemoved, clearRemoved } from './native'
import { createReactionPersistenceQueue } from './persistence'
import {
    applyBulkMessageDelete,
    applyMessageDelete,
    applyReactionAdd,
    applyReactionRemove,
    applyRemoveAll,
    applyRemoveEmoji,
    buildNativeSnapshot,
    normalizeReactionStorage,
    reactionIdentityFromPayload,
} from './state'
import { DefaultStorage } from './shared'
import type { ReactionPayload, ReactionStorage } from './shared'

type FluxEventName =
    | 'MESSAGE_REACTION_ADD'
    | 'MESSAGE_REACTION_REMOVE'
    | 'MESSAGE_REACTION_REMOVE_ALL'
    | 'MESSAGE_REACTION_REMOVE_EMOJI'
    | 'MESSAGE_DELETE'
    | 'MESSAGE_DELETE_BULK'
    | 'USER_UPDATE'
    | 'GUILD_MEMBER_UPDATE'

type IndividualReactionEvent = 'MESSAGE_REACTION_ADD' | 'MESSAGE_REACTION_REMOVE'

function payloadShape(payload: ReactionPayload) {
    const emoji = payload.emoji
    const reactionType = payload.reactionType ?? payload.reaction_type
    return {
        channel: payload.channelId != null || payload.channel_id != null,
        message: payload.messageId != null || payload.message_id != null,
        user: payload.userId != null || payload.user_id != null,
        emoji: emoji != null,
        emojiId: emoji?.id != null,
        emojiName: emoji?.name != null,
        // payload.type is Valenced/Discord's Flux action name, not the reaction kind.
        reactionType: reactionType ?? (payload.burst === true ? 'burst' : payload.burst === false ? 'normal' : null),
        burst: payload.burst ?? null,
        guild: payload.guildId != null || payload.guild_id != null,
        dispatchType: payload.type ?? null,
        keys: Object.keys(payload as object),
    }
}

function missingIdentityFields(payload: ReactionPayload): string[] {
    const missing: string[] = []
    if (payload.channelId == null && payload.channel_id == null) missing.push('channelId/channel_id')
    if (payload.messageId == null && payload.message_id == null) missing.push('messageId/message_id')
    if (payload.userId == null && payload.user_id == null) missing.push('userId/user_id')
    if (payload.emoji == null) missing.push('emoji')
    else if (payload.emoji.id == null && payload.emoji.name == null) missing.push('emoji.id/emoji.name')
    return missing
}

function copyFluxPayload(payload: ReactionPayload): ReactionPayload {
    return {
        ...payload,
        emoji: payload.emoji ? { ...payload.emoji } : payload.emoji,
        ids: Array.isArray(payload.ids) ? [...payload.ids] : payload.ids,
    }
}

export default plugin<{ jsonStorage: ReactionStorage }>({
    jsonStorage: {
        load: true,
        default: DefaultStorage,
    },

    async start({ cleanup, plugin, jsonStorage }) {
        let state = normalizeReactionStorage(jsonStorage.cache ?? DefaultStorage, Date.now())
        let storageReady = false
        let stopped = false
        let eventSequence = 0
        const pendingEvents: Array<{ event: FluxEventName; payload: ReactionPayload }> = []
        const persistence = createReactionPersistenceQueue(jsonStorage, (error, sequence) => {
            plugin.reportError('Could not verify persisted reaction history (write ' + sequence + '): ' + String(error))
            console.error('[Reaction Logs] storage write/read-back verification failed', { sequence, error })
        })

        const persistState = () => {
            const snapshot = normalizeReactionStorage(state, Date.now())
            void persistence.enqueue(snapshot).then(() => {
                console.debug('[Reaction Logs] storage write verified', {
                    removals: snapshot.removalHistory.length,
                    active: snapshot.activeReactions.length,
                })
            }, () => {
                // The queue's error callback reports the failed read-back.
            })
        }

        const syncNative = () => {
            try {
                const snapshot = buildNativeSnapshot(state)
                setRemoved(JSON.stringify(snapshot))
                console.debug('[Reaction Logs] native state synchronized', {
                    removals: snapshot.removalHistory.length,
                    active: snapshot.activeReactions.length,
                    currentRemovedPills: snapshot.activeRemovals.length,
                })
            } catch (error) {
                plugin.reportError('Failed to synchronize native reaction state: ' + String(error))
                console.error('[Reaction Logs] native synchronization failed', error)
            }
        }

        const update = (next: ReactionStorage, persist = true) => {
            state = next
            if (persist) {
                persistState()
                syncNative()
            }
        }

        const handleIndividualReaction = (
            eventName: IndividualReactionEvent,
            payload: ReactionPayload,
            transition: (current: ReactionStorage, event: ReactionPayload) => ReactionStorage,
            persist: boolean,
        ) => {
            const sequence = ++eventSequence
            const identity = reactionIdentityFromPayload(payload)
            const beforeIds = new Set(state.removalHistory.map(record => record.id))
            const next = transition(state, payload)
            const recorded = next.removalHistory.some(record => !beforeIds.has(record.id))
            console.debug('[Reaction Logs] Flux reaction event received', {
                event: eventName,
                sequence,
                deferredUntilStorageLoad: !storageReady,
                identityValid: identity != null,
                missingIdentityFields: identity ? [] : missingIdentityFields(payload),
                fields: payloadShape(payload),
                reactionType: identity?.reactionType ?? null,
                removalRecorded: recorded,
                removalCount: next.removalHistory.length,
            })
            update(next, persist)
        }

        const processEvent = (event: FluxEventName, payload: ReactionPayload, persist = true) => {
            switch (event) {
                case 'MESSAGE_REACTION_ADD':
                    handleIndividualReaction(event, payload, applyReactionAdd, persist)
                    break
                case 'MESSAGE_REACTION_REMOVE':
                    handleIndividualReaction(event, payload, applyReactionRemove, persist)
                    break
                case 'MESSAGE_REACTION_REMOVE_ALL':
                    update(applyRemoveAll(state, payload), persist)
                    break
                case 'MESSAGE_REACTION_REMOVE_EMOJI':
                    update(applyRemoveEmoji(state, payload), persist)
                    break
                case 'MESSAGE_DELETE':
                    update(applyMessageDelete(state, payload), persist)
                    break
                case 'MESSAGE_DELETE_BULK':
                    update(applyBulkMessageDelete(state, payload), persist)
                    break
                case 'USER_UPDATE':
                case 'GUILD_MEMBER_UPDATE':
                    if (persist && state.removalHistory.length > 0) syncNative()
                    break
            }
        }

        const dispatch = (event: FluxEventName, payload: ReactionPayload) => {
            if (stopped) return payload
            if (!storageReady) {
                pendingEvents.push({ event, payload: copyFluxPayload(payload) })
                console.debug('[Reaction Logs] buffered Flux event while loading persistent history', {
                    event,
                    buffered: pendingEvents.length,
                    fields: payloadShape(payload),
                })
                return payload
            }
            processEvent(event, payload)
            // Returning the original payload is required for Flux to continue
            // dispatching the action to Discord's own stores.
            return payload
        }

        // Install listeners before the first await. Otherwise a reaction removed
        // while the storage file is loading can be missed and never persisted.
        cleanup(
            onFluxEventDispatched<ReactionPayload>('MESSAGE_REACTION_ADD', payload => dispatch('MESSAGE_REACTION_ADD', payload)),
            onFluxEventDispatched<ReactionPayload>('MESSAGE_REACTION_REMOVE', payload => dispatch('MESSAGE_REACTION_REMOVE', payload)),
            onFluxEventDispatched<ReactionPayload>('MESSAGE_REACTION_REMOVE_ALL', payload => dispatch('MESSAGE_REACTION_REMOVE_ALL', payload)),
            onFluxEventDispatched<ReactionPayload>('MESSAGE_REACTION_REMOVE_EMOJI', payload => dispatch('MESSAGE_REACTION_REMOVE_EMOJI', payload)),
            onFluxEventDispatched<ReactionPayload>('MESSAGE_DELETE', payload => dispatch('MESSAGE_DELETE', payload)),
            onFluxEventDispatched<ReactionPayload>('MESSAGE_DELETE_BULK', payload => dispatch('MESSAGE_DELETE_BULK', payload)),
            onFluxEventDispatched<ReactionPayload>('USER_UPDATE', payload => dispatch('USER_UPDATE', payload)),
            onFluxEventDispatched<ReactionPayload>('GUILD_MEMBER_UPDATE', payload => dispatch('GUILD_MEMBER_UPDATE', payload)),
            async () => {
                stopped = true
                await persistence.drain()
                try {
                    clearRemoved()
                } catch (error) {
                    plugin.reportError('Could not clear native reaction bindings: ' + String(error))
                }
            },
        )

        let stored: ReactionStorage | null | undefined
        try {
            stored = await jsonStorage.get()
        } catch (error) {
            plugin.reportError('Could not load reaction history; using the last in-memory cache: ' + String(error))
            console.error('[Reaction Logs] storage read failed', error)
            stored = jsonStorage.cache
        }
        if (stopped) return

        state = normalizeReactionStorage(stored ?? jsonStorage.cache, Date.now())
        storageReady = true
        const replay = pendingEvents.splice(0)
        for (const item of replay) processEvent(item.event, item.payload, false)

        // Persist the loaded/migrated state plus every event received during load,
        // then restore the Android hooks and UI state in one synchronization pass.
        persistState()
        syncNative()
        console.debug('[Reaction Logs] started', {
            removals: state.removalHistory.length,
            active: state.activeReactions.length,
            replayedEvents: replay.length,
        })
    },
})
