import { describe, expect, test } from 'bun:test'
import { createReactionPersistenceQueue } from '../js/persistence'
import {
    copyFluxReactionPayload,
    getReactionLogFluxEvent,
    reduceReactionLogFluxPayload,
} from '../js/flux-events'
import { buildNativeSnapshot, normalizeReactionStorage } from '../js/state'
import { DefaultStorage } from '../js/shared'
import type { ReactionPayload, ReactionStorage } from '../js/shared'
import type { FluxReactionPayload } from '../js/flux-events'

const rawAdd = (userId: string): FluxReactionPayload => ({
    type: 'MESSAGE_REACTION_ADD',
    guild_id: 'guild-9',
    channel_id: 'channel-8',
    message_id: 'message-7',
    user_id: userId,
    emoji: {
        id: null,
        name: 'heart',
        src: 'https://cdn.example.invalid/heart',
        displayName: 'heart',
        animated: false,
    },
    reaction_type: 0,
})

const rawRemove = (userId: string): FluxReactionPayload => ({
    ...rawAdd(userId),
    type: 'MESSAGE_REACTION_REMOVE',
})

function dispatchAction(
    current: ReactionStorage,
    action: FluxReactionPayload,
    now: number,
): ReactionStorage {
    const reduced = reduceReactionLogFluxPayload(current, action, now)
    if (!reduced) throw new Error(`Unexpected Flux action: ${action.type}`)
    return reduced.state
}

describe('Reaction Logs raw Flux-to-state pipeline', () => {
    test('a final REMOVE creates a durable popup marker while active count remains zero', () => {
        let current = structuredClone(DefaultStorage)
        current = dispatchAction(current, rawAdd('user-1'), 10)
        expect(current.activeReactions).toHaveLength(1)
        current = dispatchAction(current, rawRemove('user-1'), 20)

        expect(current.removalHistory).toHaveLength(1)
        expect(current.removalHistory[0]).toMatchObject({
            guildId: 'guild-9',
            channelId: 'channel-8',
            messageId: 'message-7',
            userId: 'user-1',
            emojiKey: 'unicode:heart',
            reactionType: 0,
            timestamp: 20,
        })

        const popupData = buildNativeSnapshot(current, 20)
        expect(popupData.activeReactions).toHaveLength(0)
        expect(popupData.activeRemovals).toEqual([
            { pillKey: 'message-7|unicode:heart|0', removedUsers: [{ id: 'user-1' }] },
        ])
    })

    test('one user is marked removed while other users remain active on the same emoji', () => {
        let current = structuredClone(DefaultStorage)
        current = dispatchAction(current, rawAdd('user-a'), 10)
        current = dispatchAction(current, rawAdd('user-b'), 11)
        current = dispatchAction(current, rawRemove('user-a'), 12)

        const popupData = buildNativeSnapshot(current, 12)
        expect(current.removalHistory.map(item => item.userId)).toEqual(['user-a'])
        expect(current.activeReactions.map(item => item.userId)).toEqual(['user-b'])
        expect(popupData.activeRemovals).toEqual([
            { pillKey: 'message-7|unicode:heart|0', removedUsers: [{ id: 'user-a' }] },
        ])
    })

    test('repeated raw REMOVE actions do not create duplicate log records', () => {
        let current = structuredClone(DefaultStorage)
        current = dispatchAction(current, rawAdd('user-1'), 10)
        current = dispatchAction(current, rawRemove('user-1'), 20)
        current = dispatchAction(current, rawRemove('user-1'), 21)

        expect(current.removalHistory).toHaveLength(1)
        expect(buildNativeSnapshot(current, 21).activeRemovals[0].removedUsers).toEqual([
            { id: 'user-1' },
        ])
    })

    test('buffer copying preserves inherited emoji getters before replay', () => {
        const emoji = Object.create({
            get id() { return null },
            get name() { return 'wave' },
            get src() { return 'https://cdn.example.invalid/wave' },
            get displayName() { return 'wave' },
            get animated() { return false },
        }) as NonNullable<ReactionPayload['emoji']>
        const rawAction = {
            type: 'MESSAGE_REACTION_REMOVE',
            guild_id: 'guild-9',
            channel_id: 'channel-8',
            message_id: 'message-7',
            user_id: 'user-1',
            emoji,
            reaction_type: 0,
        } satisfies FluxReactionPayload

        const buffered = copyFluxReactionPayload(rawAction)
        expect(buffered.emoji).toMatchObject({
            id: null,
            name: 'wave',
            src: 'https://cdn.example.invalid/wave',
            displayName: 'wave',
            animated: false,
        })
        const reduced = reduceReactionLogFluxPayload(
            structuredClone(DefaultStorage),
            buffered,
            30,
        )
        expect(getReactionLogFluxEvent(buffered)).toBe('MESSAGE_REACTION_REMOVE')
        expect(reduced?.event).toBe('MESSAGE_REACTION_REMOVE')
        const current = reduced!.state
        expect(current.removalHistory).toHaveLength(1)
        expect(current.removalHistory[0]).toMatchObject({
            channelId: 'channel-8',
            messageId: 'message-7',
            userId: 'user-1',
            emojiKey: 'unicode:wave',
            emojiSrc: 'https://cdn.example.invalid/wave',
        })
    })

    test('persisted final removal rebuilds the original popup marker after reload', async () => {
        let disk = structuredClone(DefaultStorage)
        const storage = {
            async set(value: ReactionStorage) {
                disk = structuredClone(value)
            },
            async get() {
                return structuredClone(disk)
            },
        }

        let current = structuredClone(DefaultStorage)
        current = dispatchAction(current, rawAdd('user-1'), 10)
        current = dispatchAction(current, rawRemove('user-1'), 20)

        const queue = createReactionPersistenceQueue(storage, () => {
            throw new Error('unexpected persistence failure')
        })
        await queue.enqueue(current)
        await queue.drain()

        const restored = normalizeReactionStorage(await storage.get(), 30)
        const reconstructedPopup = buildNativeSnapshot(restored, 30)
        expect(restored.activeReactions).toHaveLength(0)
        expect(restored.removalHistory).toHaveLength(1)
        expect(reconstructedPopup.activeRemovals).toEqual([
            { pillKey: 'message-7|unicode:heart|0', removedUsers: [{ id: 'user-1' }] },
        ])
    })
})
