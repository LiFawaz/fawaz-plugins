export interface EmojiPayload {
    id?: string | number | null
    name?: string | null
    src?: string | null
    displayName?: string | null
    animated?: boolean | null
}

export interface ReactionPayload {
    // Valenced Flux dispatch discriminator (for example MESSAGE_REACTION_REMOVE).
    // This is not the normal/burst reaction type.
    type?: string
    channelId?: string | number
    channel_id?: string | number
    messageId?: string | number
    message_id?: string | number
    id?: string | number
    ids?: (string | number)[]
    userId?: string | number
    user_id?: string | number
    emoji?: EmojiPayload
    reactionType?: number | string
    reaction_type?: number | string
    burst?: boolean
    guildId?: string | number
    guild_id?: string | number
    optimistic?: boolean
}

export interface ActiveReaction {
    guildId: string | null
    channelId: string
    messageId: string
    userId: string
    emojiId: string | null
    emojiName: string | null
    emojiSrc: string | null
    emojiDisplayName: string | null
    emojiAnimated: boolean | null
    emojiKey: string
    reactionType: number
}

export interface RemovalRecord {
    id: string
    identityKey: string
    guildId: string | null
    channelId: string
    messageId: string
    userId: string
    emojiId: string | null
    emojiName: string | null
    emojiSrc: string | null
    emojiDisplayName: string | null
    emojiAnimated: boolean | null
    emojiKey: string
    reactionType: number
    timestamp: number | null
    epoch: number
}

export interface ReactionStorage {
    schemaVersion: 2
    removalHistory: RemovalRecord[]
    activeReactions: ActiveReaction[]
    epochs: Record<string, number>
    // Read only for migration from versions <= 1.2.9.
    activeRemovals?: { pillKey: string; removedUsers: string[] }[]
}

export const DefaultStorage: ReactionStorage = {
    schemaVersion: 2,
    removalHistory: [],
    activeReactions: [],
    epochs: {},
}
