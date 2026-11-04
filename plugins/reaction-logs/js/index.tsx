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

type ReactionEventName = 'MESSAGE_REACTION_ADD' | 'MESSAGE_REACTION_REMOVE'

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
        dispatchType: (payload as ReactionPayload & { type?: unknown }).type ?? null,
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

export default plugin<{ jsonStorage: ReactionStorage }>({
    jsonStorage: {
        load: true,
        default: DefaultStorage,
    },

    async start({ cleanup, plugin, jsonStorage }) {
        // Await the storage read explicitly. A plugin start can happen before the
        // async JSON document has populated cache, even with load:true configured.
        let stored: ReactionStorage | null | undefined
        try {
            stored = await jsonStorage.get()
        } catch (error) {
            plugin.reportError('Could not load reaction history; using the last in-memory cache: ' + String(error))
            console.error('[Reaction Logs] storage read failed', error)
            stored = jsonStorage.cache
        }
        let state = normalizeReactionStorage(stored ?? jsonStorage.cache, Date.now())
        let eventSequence = 0
        const persistence = createReactionPersistenceQueue(jsonStorage, (error, sequence) => {
            plugin.reportError('Could not verify persisted reaction history (write ' + sequence + '): ' + String(error))
            console.error('[Reaction Logs] storage write/read-back verification failed', { sequence, error })
        })

        const persistState = () => {
            // Capture this exact state now; the queue snapshots it again before
            // the first await so rapid Flux events cannot overwrite each other.
            const snapshot = normalizeReactionStorage(state, Date.now())
            persistence.enqueue(snapshot).then(() => {
                console.debug('[Reaction Logs] storage write verified', {
                    removals: snapshot.removalHistory.length,
                    active: snapshot.activeReactions.length,
                })
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

        const update = (next: ReactionStorage) => {
            state = next
            persistState()
            syncNative()
        }

        const handleIndividualReaction = (
            eventName: ReactionEventName,
            payload: ReactionPayload,
            transition: (current: ReactionStorage, event: ReactionPayload) => ReactionStorage,
        ) => {
            const sequence = ++eventSequence
            const identity = reactionIdentityFromPayload(payload)
            const beforeIds = new Set(state.removalHistory.map(record => record.id))
            const next = transition(state, payload)
            const recorded = next.removalHistory.some(record => !beforeIds.has(record.id))
            console.debug('[Reaction Logs] Flux reaction event received', {
                event: eventName,
                sequence,
                identityValid: identity != null,
                missingIdentityFields: identity ? [] : missingIdentityFields(payload),
                fields: payloadShape(payload),
                reactionType: identity?.reactionType ?? null,
                removalRecorded: recorded,
                removalCount: next.removalHistory.length,
            })
            update(next)
            // Valenced's Flux patch contract requires the original payload to be
            // returned for Discord's dispatcher to continue processing the event.
            return payload
        }

        cleanup(
            onFluxEventDispatched<ReactionPayload>(
                'MESSAGE_REACTION_ADD',
                payload => handleIndividualReaction('MESSAGE_REACTION_ADD', payload, applyReactionAdd),
            ),

            onFluxEventDispatched<ReactionPayload>(
                'MESSAGE_REACTION_REMOVE',
                payload => handleIndividualReaction('MESSAGE_REACTION_REMOVE', payload, applyReactionRemove),
            ),

            onFluxEventDispatched<ReactionPayload>(
                'MESSAGE_REACTION_REMOVE_ALL',
                payload => {
                    update(applyRemoveAll(state, payload))
                    return payload
                },
            ),

            onFluxEventDispatched<ReactionPayload>(
                'MESSAGE_REACTION_REMOVE_EMOJI',
                payload => {
                    update(applyRemoveEmoji(state, payload))
                    return payload
                },
            ),

            onFluxEventDispatched<ReactionPayload>(
                'MESSAGE_DELETE',
                payload => {
                    update(applyMessageDelete(state, payload))
                    return payload
                },
            ),

            onFluxEventDispatched<ReactionPayload>(
                'USER_UPDATE',
                payload => {
                    if (state.removalHistory.length > 0) syncNative()
                    return payload
                },
            ),

            onFluxEventDispatched<ReactionPayload>(
                'GUILD_MEMBER_UPDATE',
                payload => {
                    if (state.removalHistory.length > 0) syncNative()
                    return payload
                },
            ),

            onFluxEventDispatched<ReactionPayload>(
                'MESSAGE_DELETE_BULK',
                payload => {
                    update(applyBulkMessageDelete(state, payload))
                    return payload
                },
            ),

            async () => {
                // Drain queued JSON writes before native teardown. This clears only
                // native in-memory view bindings, never the persistent history.
                await persistence.drain()
                try {
                    clearRemoved()
                } catch (error) {
                    plugin.reportError('Could not clear native reaction bindings: ' + String(error))
                }
            },
        )

        // Restore saved state (including emoji render data), prune expiry, and
        // synchronize native hooks even when the popup has never been opened.
        persistState()
        syncNative()
        console.debug('[Reaction Logs] started', {
            removals: state.removalHistory.length,
            active: state.activeReactions.length,
        })
    },
})
