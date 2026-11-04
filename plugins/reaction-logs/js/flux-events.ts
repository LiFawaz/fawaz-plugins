import {
    applyBulkMessageDelete,
    applyMessageDelete,
    applyReactionAdd,
    applyReactionRemove,
    applyRemoveAll,
    applyRemoveEmoji,
} from './state'
import type { ReactionPayload, ReactionStorage } from './shared'

export type FluxEventName =
    | 'MESSAGE_REACTION_ADD'
    | 'MESSAGE_REACTION_REMOVE'
    | 'MESSAGE_REACTION_REMOVE_ALL'
    | 'MESSAGE_REACTION_REMOVE_EMOJI'
    | 'MESSAGE_DELETE'
    | 'MESSAGE_DELETE_BULK'
    | 'USER_UPDATE'
    | 'GUILD_MEMBER_UPDATE'

export type FluxReactionPayload = ReactionPayload & { type: string }

const reactionLogEventNames = new Set<string>([
    'MESSAGE_REACTION_ADD',
    'MESSAGE_REACTION_REMOVE',
    'MESSAGE_REACTION_REMOVE_ALL',
    'MESSAGE_REACTION_REMOVE_EMOJI',
    'MESSAGE_DELETE',
    'MESSAGE_DELETE_BULK',
    'USER_UPDATE',
    'GUILD_MEMBER_UPDATE',
])

/**
 * Valenced's any-event dispatcher exposes the action discriminator as payload.type.
 * Resolve it here so the live listener and regression tests exercise the same route.
 */
export function getReactionLogFluxEvent(payload: ReactionPayload): FluxEventName | null {
    const type = payload?.type
    return typeof type === 'string' && reactionLogEventNames.has(type)
        ? type as FluxEventName
        : null
}

/**
 * Events received while jsonStorage is loading must be copied before replay.
 * Read each known field explicitly: Discord model objects may expose emoji fields
 * through prototype getters, which object spread would silently discard.
 */
export function copyFluxReactionPayload(payload: FluxReactionPayload): FluxReactionPayload {
    const emoji = payload.emoji
    return {
        type: payload.type,
        channelId: payload.channelId,
        channel_id: payload.channel_id,
        messageId: payload.messageId,
        message_id: payload.message_id,
        id: payload.id,
        ids: Array.isArray(payload.ids) ? [...payload.ids] : payload.ids,
        userId: payload.userId,
        user_id: payload.user_id,
        guildId: payload.guildId,
        guild_id: payload.guild_id,
        emoji: emoji == null ? emoji : {
            id: emoji.id,
            name: emoji.name,
            src: emoji.src,
            displayName: emoji.displayName,
            animated: emoji.animated,
        },
        reactionType: payload.reactionType,
        reaction_type: payload.reaction_type,
        burst: payload.burst,
        optimistic: payload.optimistic,
    }
}

/**
 * Reduce one actual Flux action into the same state transitions used by the
 * persistent logger. The UI/native layer consumes a snapshot of this result.
 */
export function applyReactionLogFluxEvent(
    current: ReactionStorage,
    event: FluxEventName,
    payload: ReactionPayload,
    now = Date.now(),
): ReactionStorage {
    switch (event) {
        case 'MESSAGE_REACTION_ADD':
            return applyReactionAdd(current, payload, now)
        case 'MESSAGE_REACTION_REMOVE':
            return applyReactionRemove(current, payload, now)
        case 'MESSAGE_REACTION_REMOVE_ALL':
            return applyRemoveAll(current, payload, now)
        case 'MESSAGE_REACTION_REMOVE_EMOJI':
            return applyRemoveEmoji(current, payload, now)
        case 'MESSAGE_DELETE':
            return applyMessageDelete(current, payload, now)
        case 'MESSAGE_DELETE_BULK':
            return applyBulkMessageDelete(current, payload, now)
        case 'USER_UPDATE':
        case 'GUILD_MEMBER_UPDATE':
            return current
    }
}

/**
 * The production listener and tests share this single raw-payload entry point.
 * A null result means this Flux action is not owned by Reaction Logs.
 */
export function reduceReactionLogFluxPayload(
    current: ReactionStorage,
    payload: ReactionPayload,
    now = Date.now(),
): { event: FluxEventName; state: ReactionStorage } | null {
    const event = getReactionLogFluxEvent(payload)
    if (!event) return null
    return {
        event,
        state: applyReactionLogFluxEvent(current, event, payload, now),
    }
}
