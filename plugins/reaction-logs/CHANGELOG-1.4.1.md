# Reaction Logs 1.4.1

- Verify every persistent JSON write by reading the document back from disk; report silent filesystem failures.
- Serialize rapid storage updates and preserve the exact snapshot associated with each queued write.
- Register Flux listeners before storage loading and replay events received during startup after saved state is restored.
- Correct Flux diagnostics so the dispatcher action name is not mistaken for the reaction type.
- Harden message accessory binding so the pill hook resolves the actual adapter item list and channel ID across class hierarchies.
- Make zero-count reaction proxies safe for primitive interface methods and rebuild custom emoji CDN sources when no cached model exists.
- Resolve popup users from Discord's guild-member cache when the user cache lookup is unavailable.
- Keep a just-removed user row red briefly while Discord's already-open popup is still showing stale active data.
- Add persistence queue tests for rapid writes, swallowed storage failures, and queue recovery.
