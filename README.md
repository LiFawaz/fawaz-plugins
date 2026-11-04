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
| Voice Message Downloader | 1.0.8 | Saves voice messages through Android Files |

**Hidden Channels is intentionally excluded.**

## Voice Message Downloader

Every download opens Android Files. Choose **Create new file** to select a folder and filename, or **Choose existing file** to replace an audio file after a confirmation prompt. Configure also lets you save an optional default folder so the picker starts there next time.

The plugin uses Android's Storage Access Framework and a native Kotlin component. It does not save voice messages into Valenced's private Documents or Cache folders.

## Source repositories

[VC Follow](https://github.com/LiFawaz/valenced-vc-follow) · [Fake-deaf](https://github.com/LiFawaz/valenced-fake-deaf) · [Role Color Everywhere](https://github.com/LiFawaz/Role-Colors-Everywhere) · [Reaction Logs](https://github.com/LiFawaz/reaction-logs)
