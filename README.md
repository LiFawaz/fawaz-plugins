# Fawaz Plugins

A single Valenced plugin repository maintained by LiFawaz.

## Install

Add this repository URL once in Valenced:

```
https://raw.githubusercontent.com/LiFawaz/fawaz-plugins/main/index.json
```

Then open the plugin browser and install the plugins you want.

## Included plugins

| Plugin | Version | What it does |
| --- | --- | --- |
| VC Follow | 1.0.23 | Follows a user between voice channels |
| Fake-deaf | 0.2.0 | Toggles fake mute/deafen voice-state flags |
| Role Color Everywhere | 1.1.7 | Applies role colors to mentions, typing names and voice users |
| Reaction Logs | 1.2.9 | Marks removed reaction pills red locally |
| Voice Message Downloader | 1.0.2 | Saves voice-message audio to the app Documents folder |

Hidden Channels is intentionally excluded.

## Source repositories

- [VC Follow](https://github.com/LiFawaz/valenced-vc-follow)
- [Fake-deaf](https://github.com/LiFawaz/valenced-fake-deaf)
- [Role Color Everywhere](https://github.com/LiFawaz/Role-Colors-Everywhere)
- [Reaction Logs](https://github.com/LiFawaz/reaction-logs)

Voice Message Downloader source is under `plugins/voice-message-downloader`.

## Notes

Reaction Logs 1.2.9 is the latest stable version currently published by its source repository. Newer 1.3.0 builds found on feature branches are not advertised as stable releases.

Voice Message Downloader 1.0.2 fixes the plugin script wrapper: Valenced expects the script expression to return an object with a `default` property containing the plugin options object. The previous ZIP incorrectly contained TypeScript CommonJS output, which caused "Plugin options must be an object" during initialization. The script wrapper was tested with Valenced's loader contract; live Android downloading still needs device verification.
