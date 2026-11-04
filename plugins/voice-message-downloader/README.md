# Voice Message Downloader

Adds **Download Voice Message** to the long-press action sheet for native Discord voice messages.

## Saving a voice message

Every download opens Android's system file picker. The first prompt lets you choose one of two actions:

- **Create new file** opens Android's Save As picker. Choose a folder and filename. You can create a folder in the picker when the selected document provider supports it. If the same name already exists, Android creates a numbered copy instead of overwriting it.
- **Choose existing file** opens the audio file picker. Select an existing audio file; the plugin checks that the provider supports writing and asks you to confirm before replacing its contents.

## Remember a default folder

In Configure, choose **Choose or change folder** to select an optional default folder. Android remembers the write permission, and later file pickers start in that folder. The first download still lets you choose a different folder or an existing file.

## Storage and safety

The plugin uses Android's Storage Access Framework through a native Kotlin component. Audio downloads are first checked and staged in temporary app cache, then written to the selected document. Downloads are limited to 50 MB and accepted only from Discord's HTTPS attachment CDN hosts.

If the default folder is deleted or Android revokes its permission, choose it again in Configure. Existing-file replacement requires a document provider that advertises write support.
