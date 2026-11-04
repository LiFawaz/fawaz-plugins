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
    hydrateState,
} from './state'
import { DefaultStorage } from './shared'
import type { ReactionPayload, ReactionStorage } from './shared'

export default plugin<{ jsonStorage: ReactionStorage }>({
    jsonStorage: {
        load: true,
        default: DefaultStorage,
    },

    start({ cleanup, plugin, jsonStorage }) {
        let state = hydrateState(jsonStorage.cache, Date.now())
        let persistQueue = Promise.resolve()

        const persist = () => {
            const snapshot: ReactionStorage = {
                schemaVersion: 2,
                removalHistory: [...state.storage.removalHistory],
                epochs: { ...state.storage.epochs },
            }
            persistQueue = persistQueue
                .then(() => jsonStorage.set(snapshot, true))
                .catch(error => {
                    plugin.reportError('Failed to persist removed reaction history: ' + String(error))
                })
        }

        const syncNative = () => {
            try {
                setRemoved(JSON.stringify(buildNativeSnapshot(state, Date.now())))
            } catch (error) {
                plugin.reportError('Failed to update reaction popup history: ' + String(error))
            }
        }

        const update = (next: typeof state) => {
            state = next
            persist()
            syncNative()
        }

        cleanup(
            onFluxEventDispatched<ReactionPayload>(
                'MESSAGE_REACTION_ADD',
                payload => {
                    update(applyReactionAdd(state, payload))
                    // Discord remains the source of truth for active reactors and counts.
                    return payload
                },
            ),

            onFluxEventDispatched<ReactionPayload>(
                'MESSAGE_REACTION_REMOVE',
                payload => {
                    update(applyReactionRemove(state, payload))
                    // Record the affected user, then let Discord remove them from its active list.
                    return payload
                },
            ),

            onFluxEventDispatched<ReactionPayload>(
                'MESSAGE_REACTION_REMOVE_ALL',
                payload => {
                    update(applyRemoveAll(state, payload))
                    // This event has no per-user identity; do not manufacture removal records.
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
                'MESSAGE_DELETE_BULK',
                payload => {
                    update(applyBulkMessageDelete(state, payload))
                    return payload
                },
            ),

            () => {
                // Native view hooks are session-scoped. The persistent history remains in jsonStorage.
                try {
                    clearRemoved()
                } catch (error) {
                    plugin.reportError('Could not clear native reaction hooks: ' + String(error))
                }
            },
        )

        // Migrate the previous 1.2.9 active-removals cache and restore historical popup rows.
        persist()
        syncNative()
    },
})
