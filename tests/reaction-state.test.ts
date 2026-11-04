import { describe, expect, test } from 'bun:test'
import {
    applyBulkMessageDelete,
    applyMessageDelete,
    applyReactionAdd,
    applyReactionRemove,
    applyRemoveAll,
    applyRemoveEmoji,
    buildNativeSnapshot,
    hydrateState,
    normalizeStorage,
    removedUsersForPopup,
    reactionIdentity,
    REMOVAL_RETENTION_MS,
    MAX_REMOVAL_RECORDS,
} from '../plugins/reaction-logs/js/state'
import type { ReactionPayload, ReactionRuntimeState, ReactionStorage } from '../plugins/reaction-logs/js/shared'

const base = (overrides: Partial<ReactionPayload> = {}): ReactionPayload => ({
    channel_id: 'channel-1',
    message_id: 'message-1',
    guild_id: 'guild-1',
    user_id: 'user-1',
    emoji: { name: 'heart', id: null },
    reaction_type: 0,
    ...overrides,
})

const fresh = (now = 1_000_000): ReactionRuntimeState => hydrateState({}, now)

const popup = (emojiKey = 'unicode:heart', overrides: Partial<{
    guildId: string | null
    channelId: string
    messageId: string
}> = {}) => ({
    guildId: overrides.guildId ?? 'guild-1',
    channelId: overrides.channelId ?? 'channel-1',
    messageId: overrides.messageId ?? 'message-1',
    emojiKey,
})

describe('Reaction Logs exact reaction history', () => {
    test('one add then one remove stores the affected user and exact identifiers', () => {
        let state = applyReactionAdd(fresh(), base(), 1_000_001)
        expect(state.activeReactions).toHaveLength(1)

        state = applyReactionRemove(state, base(), 1_000_002)
        expect(state.activeReactions).toHaveLength(0)
        expect(state.storage.removalHistory).toHaveLength(1)
        expect(state.storage.removalHistory[0]).toMatchObject({
            guildId: 'guild-1',
            channelId: 'channel-1',
            messageId: 'message-1',
            userId: 'user-1',
            emojiKey: 'unicode:heart',
            reactionType: 0,
            timestamp: 1_000_002,
        })
    })

    test('removing one of several users does not affect the others', () => {
        let state = applyReactionAdd(fresh(), base({ user_id: 'user-a' }), 1_000_001)
        state = applyReactionAdd(state, base({ user_id: 'user-b' }), 1_000_002)
        state = applyReactionAdd(state, base({ user_id: 'user-c' }), 1_000_003)
        state = applyReactionRemove(state, base({ user_id: 'user-b' }), 1_000_004)

        expect(state.storage.removalHistory.map(item => item.userId)).toEqual(['user-b'])
        expect(state.activeReactions.map(item => item.userId).sort()).toEqual(['user-a', 'user-c'])
        expect(removedUsersForPopup(state, popup(), ['user-a', 'user-c'])).toEqual(['user-b'])
    })

    test('removing heart does not mark the same user red in laugh or star tabs', () => {
        let state = fresh()
        state = applyReactionAdd(state, base({ emoji: { name: 'heart' } }), 1_000_001)
        state = applyReactionAdd(state, base({ emoji: { name: 'laugh' } }), 1_000_002)
        state = applyReactionAdd(state, base({ emoji: { name: 'star' } }), 1_000_003)
        state = applyReactionRemove(state, base({ emoji: { name: 'heart' } }), 1_000_004)

        expect(removedUsersForPopup(state, popup('unicode:heart'))).toEqual(['user-1'])
        expect(removedUsersForPopup(state, popup('unicode:laugh'))).toEqual([])
        expect(removedUsersForPopup(state, popup('unicode:star'))).toEqual([])
        expect(state.activeReactions.map(item => item.emojiKey).sort()).toEqual([
            'unicode:laugh',
            'unicode:star',
        ])
    })

    test('same user and emoji on another message or channel remain isolated', () => {
        let state = applyReactionAdd(fresh(), base(), 1_000_001)
        state = applyReactionAdd(state, base({ message_id: 'message-2' }), 1_000_002)
        state = applyReactionAdd(state, base({ channel_id: 'channel-2' }), 1_000_003)
        state = applyReactionRemove(state, base(), 1_000_004)

        expect(state.storage.removalHistory).toHaveLength(1)
        expect(removedUsersForPopup(state, popup())).toEqual(['user-1'])
        expect(removedUsersForPopup(state, popup('unicode:heart', { messageId: 'message-2' }))).toEqual([])
        expect(removedUsersForPopup(state, popup('unicode:heart', { channelId: 'channel-2' }))).toEqual([])
        expect(state.activeReactions).toHaveLength(2)
    })

    test('guild identity is retained and the same reaction in another guild is separate', () => {
        let state = applyReactionAdd(fresh(), base({ guild_id: 'guild-a' }), 1_000_001)
        state = applyReactionAdd(state, base({ guild_id: 'guild-b' }), 1_000_002)
        state = applyReactionRemove(state, base({ guild_id: 'guild-a' }), 1_000_003)

        expect(state.storage.removalHistory[0].guildId).toBe('guild-a')
        expect(removedUsersForPopup(state, popup('unicode:heart', { guildId: 'guild-a' }))).toEqual(['user-1'])
        expect(removedUsersForPopup(state, popup('unicode:heart', { guildId: 'guild-b' }))).toEqual([])
    })

    test('another user adding the same emoji preserves the first user removal record', () => {
        let state = applyReactionAdd(fresh(), base({ user_id: 'user-a' }), 1_000_001)
        state = applyReactionRemove(state, base({ user_id: 'user-a' }), 1_000_002)
        state = applyReactionAdd(state, base({ user_id: 'user-b' }), 1_000_003)

        expect(state.storage.removalHistory.map(item => item.userId)).toEqual(['user-a'])
        expect(state.activeReactions.map(item => item.userId)).toEqual(['user-b'])
        expect(removedUsersForPopup(state, popup(), ['user-b'])).toEqual(['user-a'])
    })

    test('re-adding the same user reaction makes the current row normal but preserves history', () => {
        let state = applyReactionAdd(fresh(), base(), 1_000_001)
        state = applyReactionRemove(state, base(), 1_000_002)
        state = applyReactionAdd(state, base(), 1_000_003)

        expect(state.storage.removalHistory).toHaveLength(1)
        expect(state.activeReactions).toHaveLength(1)
        expect(removedUsersForPopup(state, popup(), ['user-1'])).toEqual([])
        expect(buildNativeSnapshot(state, 1_000_003).activeRemovals).toHaveLength(0)
    })

    test('a second remove after a re-add becomes a separate history record', () => {
        let state = applyReactionAdd(fresh(), base(), 1_000_001)
        state = applyReactionRemove(state, base(), 1_000_002)
        state = applyReactionAdd(state, base(), 1_000_003)
        state = applyReactionRemove(state, base(), 1_000_004)

        expect(state.storage.removalHistory).toHaveLength(2)
        expect(state.storage.removalHistory.map(item => item.timestamp)).toEqual([1_000_002, 1_000_004])
    })

    test('duplicate remove echoes in one cycle are deduplicated', () => {
        let state = applyReactionAdd(fresh(), base(), 1_000_001)
        state = applyReactionRemove(state, base(), 1_000_002)
        state = applyReactionRemove(state, base(), 1_000_003)

        expect(state.storage.removalHistory).toHaveLength(1)
    })

    test('a later genuine remove after an old event and plugin reload is not lost', () => {
        let state = applyReactionAdd(fresh(), base(), 1_000_001)
        state = applyReactionRemove(state, base(), 1_000_002)
        const reloaded = hydrateState(state.storage, 2_000_000)
        const later = applyReactionRemove(reloaded, base(), 2_000_001)

        expect(later.storage.removalHistory).toHaveLength(2)
    })

    test('normal and burst reaction types are kept as separate identities', () => {
        let state = applyReactionAdd(fresh(), base({ reaction_type: 0 }), 1_000_001)
        state = applyReactionAdd(state, base({ reaction_type: 1 }), 1_000_002)
        state = applyReactionRemove(state, base({ reaction_type: 1 }), 1_000_003)

        expect(state.activeReactions.map(item => item.reactionType)).toEqual([0])
        expect(state.storage.removalHistory[0].reactionType).toBe(1)
    })

    test('custom emoji uses its stable ID even when its name is null', () => {
        const identity = reactionIdentity(base({ emoji: { id: '987654321', name: null } }))
        expect(identity?.emojiKey).toBe('custom:987654321')
        expect(identity?.emojiId).toBe('987654321')
        expect(identity?.emojiName).toBeNull()
    })

    test('Unicode emoji names are NFC-normalized', () => {
        const decomposed = 'e\u0301'
        const composed = 'é'
        const one = reactionIdentity(base({ emoji: { name: decomposed } }))
        const two = reactionIdentity(base({ emoji: { name: composed } }))
        expect(one?.emojiKey).toBe('unicode:é')
        expect(one?.identityKey).toBe(two?.identityKey)
    })

    test('missing identifiers never create a fabricated removal', () => {
        const invalid: ReactionPayload[] = [
            base({ user_id: undefined }),
            base({ channel_id: undefined }),
            base({ message_id: undefined }),
            base({ emoji: undefined }),
        ]
        for (const payload of invalid) {
            expect(applyReactionRemove(fresh(), payload, 1_000_001).storage.removalHistory).toHaveLength(0)
        }
    })

    test('bulk reaction removal clears runtime state without fabricating individual records', () => {
        let state = applyReactionAdd(fresh(), base(), 1_000_001)
        state = applyRemoveAll(state, base(), 1_000_002)
        expect(state.activeReactions).toHaveLength(0)
        expect(state.storage.removalHistory).toHaveLength(0)

        state = applyReactionAdd(fresh(), base(), 1_000_003)
        state = applyRemoveEmoji(state, base(), 1_000_004)
        expect(state.activeReactions).toHaveLength(0)
        expect(state.storage.removalHistory).toHaveLength(0)
    })

    test('deleting one message cleans only its history and active state', () => {
        let state = applyReactionAdd(fresh(), base(), 1_000_001)
        state = applyReactionRemove(state, base(), 1_000_002)
        state = applyReactionAdd(state, base({ message_id: 'message-2' }), 1_000_003)
        state = applyReactionRemove(state, base({ message_id: 'message-2' }), 1_000_004)
        state = applyMessageDelete(state, { id: 'message-1', channel_id: 'channel-1' }, 1_000_005)

        expect(state.storage.removalHistory.map(item => item.messageId)).toEqual(['message-2'])
        expect(state.activeReactions).toHaveLength(0)
    })

    test('bulk message deletion clears every listed message', () => {
        let state = applyReactionRemove(applyReactionAdd(fresh(), base(), 1_000_001), base(), 1_000_002)
        state = applyReactionRemove(
            applyReactionAdd(state, base({ message_id: 'message-2' }), 1_000_003),
            base({ message_id: 'message-2' }),
            1_000_004,
        )
        state = applyBulkMessageDelete(state, { ids: ['message-1', 'message-2'], channel_id: 'channel-1' }, 1_000_005)
        expect(state.storage.removalHistory).toHaveLength(0)
    })

    test('legacy 1.2.9 storage migrates named users but never unknown "*" records', () => {
        const migrated = normalizeStorage({
            activeRemovals: [
                { pillKey: 'message-1|heart:123456789|0', removedUsers: ['user-1', '*'] },
            ],
        }, 1_000_001)

        expect(migrated.removalHistory).toHaveLength(1)
        expect(migrated.removalHistory[0]).toMatchObject({
            messageId: 'message-1',
            userId: 'user-1',
            emojiKey: 'custom:123456789',
            channelId: '',
        })
    })

    test('legacy records survive hydration and a later event update', () => {
        const migrated = normalizeStorage({
            activeRemovals: [
                { pillKey: 'message-1|heart:123456789|0', removedUsers: ['user-1'] },
            ],
        }, 1_000_001)
        const restored = hydrateState(migrated, 1_000_002)
        const next = applyReactionAdd(restored, base({ message_id: 'another-message' }), 1_000_003)

        expect(next.storage.removalHistory).toHaveLength(1)
        expect(removedUsersForPopup(next, popup('custom:123456789'))).toEqual(['user-1'])
    })

    test('removal history expires after retention', () => {
        const state = applyReactionRemove(applyReactionAdd(fresh(), base(), 1_000_001), base(), 1_000_002)
        const expired = normalizeStorage(state.storage, 1_000_002 + REMOVAL_RETENTION_MS + 1)
        expect(expired.removalHistory).toHaveLength(0)
    })

    test('retained removal history stays bounded', () => {
        const records = Array.from({ length: MAX_REMOVAL_RECORDS + 10 }, (_, index) => ({
            id: String(index),
            identityKey: JSON.stringify(['guild-1', 'channel-1', `message-${index}`, 'unicode:heart', 0, 'user-1']),
            guildId: 'guild-1',
            channelId: 'channel-1',
            messageId: `message-${index}`,
            userId: 'user-1',
            emojiId: null,
            emojiName: 'heart',
            emojiKey: 'unicode:heart',
            reactionType: 0,
            timestamp: 1_000_000 + index,
            epoch: 0,
        }))
        const normalized = normalizeStorage({ schemaVersion: 2, removalHistory: records, epochs: {} }, 1_001_000)
        expect(normalized.removalHistory).toHaveLength(MAX_REMOVAL_RECORDS)
    })

    test('session metadata survives event updates and appears in the native snapshot', () => {
        const state = applyReactionAdd(fresh(1_000_000), base(), 1_000_001)
        const next = applyReactionRemove(state, base(), 1_000_002)
        expect(next.sessionStartedAt).toBe(1_000_000)
        expect(buildNativeSnapshot(next, 1_000_002).sessionStartedAt).toBe(1_000_000)
    })
})
