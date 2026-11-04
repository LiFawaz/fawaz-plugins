export interface EmojiPayload {
    id?: string | null
    name?: string | null
}

export interface ReactionPayload {
    channelId?: string
    channel_id?: string
    messageId?: string
    message_id?: string
    id?: string
    ids?: string[]
    userId?: string
    user_id?: string
    emoji?: EmojiPayload
    reactionType?: number
    reaction_type?: number
    type?: number | string
    burst?: boolean
    guildId?: string
    guild_id?: string
    optimistic?: boolean
}

export interface ReactionIdentity {
    identityKey: string
    guildId: string | null
    channelId: string
    messageId: string
    userId: string
    emojiId: string | null
    emojiName: string | null
    emojiKey: string
    reactionType: number
}

export interface RemovalRecord extends ReactionIdentity {
    id: string
    timestamp: number
    epoch: number
}

export interface ActiveReaction extends ReactionIdentity {}

export interface ReactionStorage {
    schemaVersion: 2
    removalHistory: RemovalRecord[]
    epochs: Record<string, number>
    /** Read-only migration input from Reaction Logs <= 1.2.9. */
    activeRemovals?: { pillKey: string; removedUsers: (string | { id: string })[] }[]
}

export interface ReactionRuntimeState {
    storage: ReactionStorage
    /** Session-only view of active reactions received while this plugin is loaded. */
    activeReactions: ActiveReaction[]
}

export interface PopupReactionContext {
    guildId: string | null
    channelId: string
    messageId: string
    emojiKey: string
}

export const DefaultStorage: ReactionStorage = {
    schemaVersion: 2,
    removalHistory: [],
    epochs: {},
}
