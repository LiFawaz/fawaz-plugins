# Voice Message Downloader

Choose a destination folder once in **Configure → Choose or change folder**.

After the folder is selected, long-press a Discord voice message and tap **Download Voice Message**. The audio is downloaded straight into the saved folder automatically. No file picker, create-file prompt, or overwrite prompt appears for each download.

The plugin creates a separate file for each message and does not overwrite existing files. Filenames include the Discord message ID to avoid collisions.

## Storage and safety

The plugin uses Android's Storage Access Framework. Android grants persistent write permission to the selected folder, and the plugin stores that folder reference for later downloads. The audio is staged temporarily in app cache and then written to the selected folder.

Downloads are limited to 50 MB and accepted only from Discord's HTTPS attachment CDN hosts. If Android revokes access or the folder is deleted, select a folder again in Configure.
