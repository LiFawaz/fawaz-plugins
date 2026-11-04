import { onAnyFluxEventDispatched } from '@valenced/discord/flux'
import { setRemoved, clearRemoved } from './native'
import { createReactionPersistenceQueue } from './persistence'
import {
    copyFluxReactionPayload,
    getReactionLogFluxEvent,
    reduceReactionLogFluxPayload,
} from './flux-events'
import type { FluxEventName, FluxReactionPayload } from './flux-events'
import {
    buildNativeSnapshot,
    normalizeReactionStorage,
    reactionIdentityFromPayload,
} from './state'
import { DefaultStorage } from './shared'
import type { ReactionStorage } from './shared'

function payloadShape(payload: FluxReactionPayload) {
    const emoji = payload.emoji
    const reactionType = payload.reactionType ?? payload.reaction_type
    return {
        channel: payload.channelId != null || payload.channel_id != null,
        message: payload.messageId != null || payload.message_id != null,
        user: payload.userId != null || payload.user_id != null,
        emoji: emoji != null,
        emojiId: emoji?.id != null,
        emojiName: emoji?.name != null,
        reactionType: reactionType ?? (payload.burst === true ? 'burst' : payload.burst === false ? 'normal' : null),
        burst: payload.burst ?? null,
        guild: payload.guildId != null || payload.guild_id != null,
        dispatchType: payload.type ?? null,
        keys: Object.keys(payload as object),
    }
}

function missingIdentityFields(payload: FluxReactionPayload): string[] {
    const missing: string[] = []
    if (payload.channelId == null && payload.channel_id == null) missing.push('channelId/channel_id')
    if (payload.messageId == null && payload.message_id == null) missing.push('messageId/message_id')
    if (payload.userId == null && payload.user_id == null) missing.push('userId/user_id')
    if (payload.emoji == null) missing.push('emoji')
    else if (payload.emoji.id == null && payload.emoji.name == null) missing.push('emoji.id/emoji.name')
    return missing
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
        const pendingEvents: FluxReactionPayload[] = []
        const persistence = createReactionPersistenceQueue({
            set: (value, replace) => jsonStorage.set(value, replace ?? true),
            get: () => jsonStorage.get(),
        }, (error, sequence) => {
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

        const processEvent = (payload: FluxReactionPayload, persist = true) => {
            const reduced = reduceReactionLogFluxPayload(state, payload)
            if (!reduced) return

            const { event, state: next } = reduced
            if (event === 'USER_UPDATE' || event === 'GUILD_MEMBER_UPDATE') {
                if (persist && state.removalHistory.length > 0) syncNative()
                return
            }

            const individual = event === 'MESSAGE_REACTION_ADD' || event === 'MESSAGE_REACTION_REMOVE'
            const sequence = individual ? ++eventSequence : eventSequence
            const identity = individual ? reactionIdentityFromPayload(payload) : null
            const beforeIds = individual
                ? new Set(state.removalHistory.map(record => record.id))
                : null
            if (individual) {
                const recorded = next.removalHistory.some(record => !beforeIds?.has(record.id))
                console.debug('[Reaction Logs] Flux reaction event received', {
                    event,
                    sequence,
                    deferredUntilStorageLoad: !storageReady,
                    identityValid: identity != null,
                    missingIdentityFields: identity ? [] : missingIdentityFields(payload),
                    fields: payloadShape(payload),
                    reactionType: identity?.reactionType ?? null,
                    removalRecorded: event === 'MESSAGE_REACTION_REMOVE' && recorded,
                    removalCount: next.removalHistory.length,
                })
            }
            update(next, persist)
        }

        const dispatch = (payload: FluxReactionPayload) => {
            if (stopped) return payload

            const event = getReactionLogFluxEvent(payload)
            if (!event) {
                const action = payload?.type
                if (typeof action === 'string' && action.includes('REACTION')) {
                    console.debug('[Reaction Logs] observed an unhandled reaction Flux action', {
                        action,
                        keys: Object.keys(payload as object),
                    })
                }
                return payload
            }

            if (!storageReady) {
                pendingEvents.push(copyFluxReactionPayload(payload))
                if (event === 'MESSAGE_REACTION_REMOVE' || event === 'MESSAGE_REACTION_ADD') {
                    console.debug('[Reaction Logs] buffered Flux reaction event while loading persistent history', {
                        event,
                        buffered: pendingEvents.length,
                        fields: payloadShape(payload),
                    })
                }
                return payload
            }

            processEvent(payload)
            // Flux observers must return the original action so Discord's own
            // dispatcher and stores continue to receive it unchanged.
            return payload
        }

        // Register before the first await so early removals are captured.
        // onAnyFluxEventDispatched exposes the raw Flux action in payload.type.
        cleanup(
            onAnyFluxEventDispatched(payload => dispatch(payload as FluxReactionPayload)),
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
            stored = jsonStorage.cache as ReactionStorage | undefined
        }
        if (stopped) return

        state = normalizeReactionStorage(stored ?? jsonStorage.cache, Date.now())
        storageReady = true
        const replay = pendingEvents.splice(0)
        for (const payload of replay) processEvent(payload, false)

        // Save migrated history and all events buffered during storage loading.
        persistState()
        syncNative()
        console.debug('[Reaction Logs] started', {
            removals: state.removalHistory.length,
            active: state.activeReactions.length,
            replayedEvents: replay.length,
        })
    },
})
