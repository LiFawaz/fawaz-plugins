import { describe, expect, test } from 'bun:test'
import { createReactionPersistenceQueue } from '../js/persistence'
import { applyReactionRemove } from '../js/state'
import { DefaultStorage } from '../js/shared'
import type { ReactionStorage } from '../js/shared'

const snapshot = (messageId: string): ReactionStorage => applyReactionRemove(
    structuredClone(DefaultStorage),
    {
        channel_id: 'channel-1',
        message_id: messageId,
        user_id: 'user-1',
        emoji: { name: 'heart', id: null },
    },
    100,
)

describe('Reaction Logs durable storage queue', () => {
    test('rapid consecutive snapshots are written serially and the newest state wins', async () => {
        let disk = structuredClone(DefaultStorage)
        let concurrentWrites = 0
        let maxConcurrentWrites = 0
        const storage = {
            async set(value: ReactionStorage) {
                concurrentWrites++
                maxConcurrentWrites = Math.max(maxConcurrentWrites, concurrentWrites)
                await new Promise(resolve => setTimeout(resolve, 10))
                disk = structuredClone(value)
                concurrentWrites--
            },
            async get() {
                return structuredClone(disk)
            },
        }
        const errors: unknown[] = []
        const queue = createReactionPersistenceQueue(storage, error => errors.push(error))

        const first = queue.enqueue(snapshot('message-1'))
        const second = queue.enqueue(snapshot('message-2'))
        const third = queue.enqueue(snapshot('message-3'))
        await Promise.all([first, second, third])
        await queue.drain()

        expect(maxConcurrentWrites).toBe(1)
        expect(disk.removalHistory.map(item => item.messageId)).toEqual(['message-3'])
        expect(errors).toHaveLength(0)
    })

    test('a verified REMOVE snapshot is still present after a storage reload', async () => {
        let disk = structuredClone(DefaultStorage)
        const storage = {
            async set(value: ReactionStorage) {
                disk = structuredClone(value)
            },
            async get() {
                return structuredClone(disk)
            },
        }
        const queue = createReactionPersistenceQueue(storage, () => {
            throw new Error('unexpected persistence failure')
        })

        await queue.enqueue(snapshot('message-persisted'))
        await queue.drain()
        const reloaded = await storage.get()

        expect(reloaded.removalHistory).toHaveLength(1)
        expect(reloaded.removalHistory[0]).toMatchObject({
            channelId: 'channel-1',
            messageId: 'message-persisted',
            userId: 'user-1',
            emojiKey: 'unicode:heart',
        })
    })

    test('detects a filesystem write failure swallowed by JsonStorage.set', async () => {
        const disk = structuredClone(DefaultStorage)
        const errors: Array<{ error: unknown; sequence: number }> = []
        const storage = {
            async set(_value: ReactionStorage) {
                // Valenced JsonStorage catches writeFile errors and resolves.
            },
            async get() {
                return structuredClone(disk)
            },
        }
        const queue = createReactionPersistenceQueue(storage, (error, sequence) => {
            errors.push({ error, sequence })
        })

        await expect(queue.enqueue(snapshot('message-failed'))).rejects.toThrow(
            'read-back did not match',
        )
        await queue.drain()

        expect(errors).toHaveLength(1)
        expect(errors[0].sequence).toBe(1)
        expect(disk.removalHistory).toHaveLength(0)
    })

    test('queue continues after a failed write and persists the next snapshot', async () => {
        let disk = structuredClone(DefaultStorage)
        let writes = 0
        const errors: unknown[] = []
        const storage = {
            async set(value: ReactionStorage) {
                writes++
                if (writes === 1) return
                disk = structuredClone(value)
            },
            async get() {
                return structuredClone(disk)
            },
        }
        const queue = createReactionPersistenceQueue(storage, error => errors.push(error))

        await expect(queue.enqueue(snapshot('failed-first'))).rejects.toThrow()
        await queue.enqueue(snapshot('successful-second'))
        await queue.drain()

        expect(errors).toHaveLength(1)
        expect(disk.removalHistory.map(item => item.messageId)).toEqual(['successful-second'])
    })
})
