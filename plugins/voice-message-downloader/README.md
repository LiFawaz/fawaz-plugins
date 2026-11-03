# Voice Message Downloader

Adds **Download Voice Message** to the long-press action sheet for native Discord voice messages.

The plugin checks Discord's `IS_VOICE_MESSAGE` flag, validates the audio attachment, reads the attachment as base64 through Valenced's native file API, and writes it under the Valenced app Documents directory in `voice-messages/`.

This version no longer relies on Discord's internal `downloadMediaAsset` lookup. The save location is app Documents, not the public Android Downloads folder. After a successful write, the plugin displays the saved path.

The integration still depends on Discord's `LongPressMessageActionSheet` component. It must be tested on the target Valenced/Discord build before being described as live-device verified.
