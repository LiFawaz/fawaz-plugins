import { onFluxEventDispatched } from '@valenced/discord/flux'
import { setRemoved, clearRemoved } from './native'
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
    return {
        channel: payload.channelId != null || payload.channel_id != null,
        message: payload.messageId != null || payload.message_id != null,
        user: payload.userId != null || payload.user_id != null,
        emoji: emoji != null,
        emojiId: emoji?.id != null,
        emojiName: emoji?.name != null,
        reactionType: typeof payload.reactionType === 'number' || typeof payload.reaction_type === 'number',
        guild: payload.guildId != null || payload.guild_id != null,
    }
}

export default plugin<{ jsonStorage: ReactionStorage }>({
    jsonStorage: {
        load: true,
        default: DefaultStorage,
    },

    async start({ cleanup, plugin, jsonStorage }) {
        // Await the storage read explicitly. A plugin start can happen before the
        // async JSON document has populated cache, even with load:true configured.
        const stored = await jsonStorage.get()
        let state = normalizeReactionStorage(stored ?? jsonStorage.cache, Date.now())
        let persistQueue: Promise<void> = Promise.resolve()
        let persistSequence = 0
        let eventSequence = 0

        const persistState = () => {
            const sequence = ++persistSequence
            // Capture this exact state now; never serialize a later mutable state
            // from inside the queued callback.
            const snapshot = normalizeReactionStorage(state, Date.now())
            const removalCount = snapshot.removalHistory.length
            const activeCount = snapshot.activeReactions.length
            persistQueue = persistQueue
                .then(async () => {
                    await jsonStorage.set(snapshot, true)
                    console.debug('[Reaction Logs] storage write completed', {
                        sequence,
                        removals: removalCount,
                        active: activeCount,
                    })
                })
                .catch(error => {
                    plugin.reportError('Failed to persist reaction history: ' + String(error))
                    console.error('[Reaction Logs] storage write failed', { sequence, error })
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
            const before = state.removalHistory.length
            const next = transition(state, payload)
            const recorded = next.removalHistory.length > before
            console.debug('[Reaction Logs] Flux reaction event received', {
                event: eventName,
                sequence,
                identityValid: identity != null,
                fields: payloadShape(payload),
                reactionType: identity?.reactionType ?? null,
                removalRecorded: recorded,
                removalCount: next.removalHistory.length,
            })
            update(next)
            // Valenced's Flux patch contract uses the returned payload to allow
            // the original Discord dispatcher to continue processing the event.
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

            () => {
                // This clears only native in-memory view bindings. Persistent
                // JSON history remains untouched and is restored on the next start.
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
