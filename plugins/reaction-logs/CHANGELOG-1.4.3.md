# Reaction Logs 1.4.3

- Route reaction history through Valenced's any-Flux-event observer and dispatch on the actual action discriminator in `payload.type`.
- Preserve inherited Discord emoji model getters when copying events buffered during persistent storage initialization.
- Keep Flux action names separate from normal/burst reaction types; an explicit `burst: false` no longer overrides a supplied numeric reaction type.
- Add raw Flux-action pipeline tests for final removal, removal alongside other active users, duplicate events, buffered emoji metadata, persistence reload, and original-popup reconstruction.
- Preserve the original Discord Manage Reactions popup, zero-count synthetic reaction tabs, real user/profile rows, and persisted removal history.
