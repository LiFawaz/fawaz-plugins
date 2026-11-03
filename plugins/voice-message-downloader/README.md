# Voice Message Downloader

Adds **Download Voice Message** to the long-press action sheet for native Discord voice messages.

Version 1.0.3 downloads the attachment with JavaScript `fetch()` and writes it through Valenced's `FileModule.writeFile('documents', path, data, 'base64')` API. It no longer calls `FileModule.readAsset()` for a remote CDN URL, which could fail on Android with `Resource id #0x0`.

Files are saved under the Valenced app's Documents directory in `voice-messages/`. This is app storage, not the public Android Downloads folder.

The source is plain JavaScript (`js/index.js`), not TypeScript. The plugin script uses the Valenced `plugin()` lifecycle API and returns the `{ default: options }` object expected by the plugin loader.

If Discord's CDN link has expired, the plugin displays the HTTP status. If writing to Documents fails, it reports that as a separate error.

Live download verification still depends on testing this build on the target Android/Valenced version.
