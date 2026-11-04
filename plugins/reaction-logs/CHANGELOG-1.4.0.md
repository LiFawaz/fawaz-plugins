# Reaction Logs 1.4.0

- Restore the complete Kotlin and TypeScript source in the Fawaz Plugins repository.
- Persist exact per-user reaction-removal history separately from active reactions.
- Keep ADD and REMOVE events flowing through Discord's Flux dispatcher.
- Restore removed users inside Discord's original Manage Reactions popup using Discord's own user-row model.
- Scope popup records by channel, message, emoji, reaction type and affected user.
- Support custom emoji IDs even when the emoji name is unavailable.
- Deduplicate repeated removal events, preserve history across re-adds, and expire old records after 30 days.
- Clear records for deleted messages and avoid inventing users for bulk removal events.
