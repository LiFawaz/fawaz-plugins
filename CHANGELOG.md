# Changelog

## Voice Message Downloader 1.0.4

Added a Configure screen to select persistent app Documents or temporary app Cache storage and a custom folder path. Folder traversal is stripped, nested folder paths are supported, CDN download and Android file-write failures are reported separately, and base64 conversion no longer depends on a global `btoa` implementation.

## Repository maintenance

Corrected the Role Color Everywhere 1.1.7 package checksum using its successful GitHub Actions build ZIP. Added CI checks for package sizes, SHA-256 hashes, ZIP integrity, and manifest ID/version consistency. Hidden Channels remains excluded.
