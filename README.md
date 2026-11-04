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
| Voice Message Downloader | 1.0.6 | Saves voice messages directly into a chosen Android Files folder |

**Hidden Channels is intentionally excluded.**

## Voice Message Downloader storage

Open its **Configure** section and tap **Choose or change folder**. Android's system file picker opens. Select a folder or create a new one where the file provider supports it. The plugin remembers the folder and saves future voice messages there automatically. The first download opens the picker when no folder is configured.

The downloader uses Android's Storage Access Framework and a native Kotlin component. It writes audio into the selected folder instead of Valenced's private Documents or Cache storage. It does not copy a private app path to the clipboard.

## Source repositories

[VC Follow](https://github.com/LiFawaz/valenced-vc-follow) · [Fake-deaf](https://github.com/LiFawaz/valenced-fake-deaf) · [Role Color Everywhere](https://github.com/LiFawaz/Role-Colors-Everywhere) · [Reaction Logs](https://github.com/LiFawaz/reaction-logs)

## Repository checks

GitHub Actions validates locally hosted package sizes, SHA-256 hashes, ZIP integrity, and manifest ID/version consistency. It also checks the index, JavaScript syntax, and the Voice Message Downloader's Android folder-picker and native bridge contract.
