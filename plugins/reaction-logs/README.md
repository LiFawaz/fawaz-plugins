# Reaction Logs

Valenced plugin source for recording per-user reaction removals and showing removed users in Discord's original Manage Reactions popup.

The JavaScript side listens to Valenced Flux events and stores the event history with the plugin's persistent `jsonStorage` API. The Android side patches Discord's existing Manage Reactions model and uses Discord's `ReactionUserItem` rows so restored profiles keep their real user data, avatars and profile navigation.

Build from the repository root with:

```sh
gradle --no-daemon packageReactionLogs
```

The installable package is generated at `build/dist/lifawaz.reaction-logs@1.4.0.zip`.
