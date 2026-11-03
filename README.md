# Fawaz Plugins

A unified Valenced plugin repository maintained by LiFawaz.

## Install

Add this repository URL once in Valenced:

```text
https://raw.githubusercontent.com/LiFawaz/fawaz-plugins/main/index.json
```

Then open the plugin browser and install the plugins you want.

## Included plugins

| Plugin | Version | What it does |
| --- | --- | --- |
| VC Follow | 1.0.23 | Follows a user between Discord voice channels |
| Fake-deaf | 0.2.0 | Toggles fake mute/deafen voice-state flags |
| Role Color Everywhere | 1.1.7 | Applies role colors to mentions, typing names and voice users |
| Reaction Logs | 1.2.9 | Marks removed reaction pills red locally |
| Voice Message Downloader | 1.0.4 | Downloads voice audio and lets you choose app Documents or Cache and a folder |

**Hidden Channels is intentionally excluded.**

## Voice Message Downloader storage

Open its **Configure** section. Choose **Documents** for persistent app storage or **Cache** for temporary app storage, then choose a folder name. Nested folders are supported, for example `Audio/Discord`.

Valenced's exposed file API supports only app Documents and Cache. The plugin cannot save directly to the public Android Downloads folder through the current API. The plugin source is plain JavaScript under `plugins/voice-message-downloader/js/index.js`.

## Source repositories

[VC Follow](https://github.com/LiFawaz/valenced-vc-follow) · [Fake-deaf](https://github.com/LiFawaz/valenced-fake-deaf) · [Role Color Everywhere](https://github.com/LiFawaz/Role-Colors-Everywhere) · [Reaction Logs](https://github.com/LiFawaz/reaction-logs)

## Repository checks

GitHub Actions validates locally hosted package sizes, SHA-256 hashes, ZIP integrity, and manifest ID/version consistency. It also checks the index, JavaScript syntax, and the Voice Message Downloader's Configure UI and save flow.
