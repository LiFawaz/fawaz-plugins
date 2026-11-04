import type { ReactionStorage } from './shared'

export interface ReactionStorageAdapter {
    set(value: ReactionStorage, replace?: boolean): Promise<void>
    get(): Promise<ReactionStorage>
}

/**
 * Valenced's JsonStorage.set() catches filesystem write errors internally and
 * resolves anyway. Read the document back so callers can distinguish a durable
 * write from a swallowed write failure.
 */
export async function persistAndVerifyReactionStorage(
    storage: ReactionStorageAdapter,
    snapshot: ReactionStorage,
): Promise<void> {
    await storage.set(snapshot, true)
    const persisted = await storage.get()
    if (!persisted || JSON.stringify(persisted) !== JSON.stringify(snapshot)) {
        throw new Error('Reaction history read-back did not match the queued snapshot')
    }
}

export function createReactionPersistenceQueue(
    storage: ReactionStorageAdapter,
    onError: (error: unknown, sequence: number) => void,
) {
    let queue: Promise<void> = Promise.resolve()
    let sequence = 0

    return {
        enqueue(snapshot: ReactionStorage): Promise<void> {
            const currentSequence = ++sequence
            // Freeze the exact state at enqueue time. A later Flux event must not
            // mutate the document that is currently waiting to be written.
            const captured = JSON.parse(JSON.stringify(snapshot)) as ReactionStorage
            const operation = queue.then(() => persistAndVerifyReactionStorage(storage, captured))
            // Keep the internal queue alive after a failed write, but return the
            // original rejecting operation so the caller cannot log false success.
            queue = operation.catch(error => {
                try {
                    onError(error, currentSequence)
                } catch {
                    // An error reporter must never poison the persistence queue.
                }
            })
            return operation
        },
        drain(): Promise<void> {
            return queue
        },
    }
}
