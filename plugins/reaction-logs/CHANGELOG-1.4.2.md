# Reaction Logs 1.4.2

- Fix removed-user popup rows by converting the chat-bridge emoji into Discord's distinct API reaction emoji model required by ReactionUserItem.
- Keep Discord's original profile row model, user, guild member, avatar rendering, display-name resolution, and profile navigation.
- Intercept null popup models after the final live reaction disappears and rebuild the original popup's zero-count emoji tabs from current removal markers.
- Merge normal and burst removals within the emoji-scoped user list while preserving type-specific reaction pills and removal identity.
- Prefer the Gateway burst flag for the reaction type; never interpret the Flux action discriminator as the numeric reaction type.
- Match the required Flux dispatcher payload type explicitly and preserve the original event payload for normal pass-through.
- Add Gateway-shaped removal and burst-priority regression tests.
