# Voice Message Downloader

Adds **Download Voice Message** to the long-press action sheet for native Discord voice messages.

## Configure

Open the plugin's Configure section. Choose **Documents** to keep files in the app's persistent documents storage or **Cache** for temporary storage. Enter a folder name such as `voice-messages` or a nested path such as `Audio/Discord`.

## Saving behaviour

The plugin downloads the attachment from Discord's CDN with `fetch()`, converts the returned bytes to base64, and writes the file using Valenced's `FileModule.writeFile()` API. It does not use `FileModule.readAsset()` on a remote URL, which failed on some Android builds with `Resource id #0x0`.

Valenced exposes only app Documents and Cache through its current file API. This plugin cannot write directly to the public Android Downloads folder or invoke Android's folder picker. Files are saved under the folder selected in Configure, inside the selected app storage location.

The source is plain JavaScript. A smoke test covers the plugin loader wrapper, Configure callbacks, voice-message menu injection, CDN fetch, byte conversion, and selected storage path. Live Android behavior still needs testing on the user's installed Valenced build.
