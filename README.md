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
| Voice Message Downloader | 1.0.3 | Downloads voice audio from Discord CDN and saves it to app Documents |

Hidden Channels is intentionally excluded.

## Source repositories

- [VC Follow](https://github.com/LiFawaz/valenced-vc-follow)
- [Fake-deaf](https://github.com/LiFawaz/valenced-fake-deaf)
- [Role Color Everywhere](https://github.com/LiFawaz/Role-Colors-Everywhere)
- [Reaction Logs](https://github.com/LiFawaz/reaction-logs)

Voice Message Downloader source is under `plugins/voice-message-downloader/js/index.js`. It is plain JavaScript rather than TypeScript.

## Notes

Reaction Logs 1.2.9 is still the latest stable version published by its source repository. Newer 1.3.0 builds are from a feature branch and remain experimental until tested and promoted.

Voice Message Downloader 1.0.3 removes the remote `FileModule.readAsset()` call and instead uses JavaScript `fetch()` for the Discord CDN request, followed by Valenced's `FileModule.writeFile()` for app Documents storage. It also reports network and filesystem errors separately. The code passes a mocked loader/download test; live Android behavior still requires device verification.
