# Reaction Logs 1.3.0

- Restore and preserve the original Reaction Logs 1.2.9 source in the plugin catalog repository.
- Keep active reactions separate from persistent per-user removal history.
- Match removals by guild, channel, message, normalized emoji identity, reaction type, and affected user.
- Preserve removal history across re-adds; suppress the red state when that user is currently active again.
- Return Flux event payloads so Discord continues updating its active reactor list and counts.
- Reinsert missing historical users into Discord's existing Manage Reactions model using real cached User and GuildMember data.
- Preserve the existing popup profile navigation and avatar rendering.
- Migrate legacy 1.2.9 removal markers and retain them for 30 days, capped at 1,000 records.
- Do not create individual user records for Discord's bulk reaction-removal events.
