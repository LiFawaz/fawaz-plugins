# Voice Message Downloader

Adds **Download Voice Message** to the long-press action sheet for native Discord voice messages.

## Choose where audio is saved

Open the plugin's **Configure** section and tap **Choose or change folder**. Android's system file picker opens. Select an existing folder, or create a new folder in the picker when the selected file provider offers that option.

The selected folder is remembered. The first download also opens the picker automatically when no folder has been selected yet.

## Saving behaviour

The native Android component uses Android's Storage Access Framework with ACTION_OPEN_DOCUMENT_TREE. It takes a persistable write permission for the selected folder and stores the folder URI in the plugin's private data directory. The permission is intended to survive plugin restarts and Android process restarts.

When a voice message is downloaded, the native component streams the Discord CDN attachment directly into a new document in the selected folder. The audio is not stored in Valenced's private Documents or Cache folders, and the plugin does not copy a private app path to the clipboard.

If Android revokes access to the folder or the folder is removed, open Configure and choose another folder. Downloads are limited to 50 MB and are accepted only from Discord's HTTPS attachment CDN hosts.

Some Android system locations may be restricted by the system picker, and folder creation depends on the selected document provider.
