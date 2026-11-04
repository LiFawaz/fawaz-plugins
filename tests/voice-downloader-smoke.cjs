const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');

const root = path.resolve(__dirname, '..');
const jsPath = path.join(root, 'plugins/voice-message-downloader/js/index.js');
const ktPath = path.join(root, 'plugins/voice-message-downloader/src/main/kotlin/com/lifawaz/voicemessagedownloader/VoiceMessageDownloader.kt');
const manifestPath = path.join(root, 'plugins/voice-message-downloader/manifest.json');
const js = fs.readFileSync(jsPath, 'utf8');
const kotlin = fs.readFileSync(ktPath, 'utf8');
const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));

assert.equal(manifest.id, 'com.lifawaz.voice-message-downloader');
assert.equal(manifest.version, '1.0.7');
assert.equal(manifest.dist.script, 'index.js');
assert.equal(manifest.dist.android.path, 'plugin.jar');
assert.equal(manifest.dist.android.class, 'com.lifawaz.voicemessagedownloader.VoiceMessageDownloader');

assert.match(js, /saveToFolder/);
assert.match(js, /Choose or change folder/);
assert.match(js, /\.chooseFolder/);
assert.match(kotlin, /Intent\.ACTION_OPEN_DOCUMENT_TREE/);
assert.match(kotlin, /takePersistableUriPermission/);
assert.match(kotlin, /DocumentsContract\.createDocument/);
assert.match(kotlin, /openOutputStream\(document, "wt"\)/);
assert.match(kotlin, /requireAppContext/);
assert.match(kotlin, /pluginDir/);
assert.match(kotlin, /MAX_AUDIO_BYTES/);

assert.doesNotMatch(js, /saveWithPicker|Create new file|Choose existing file|DocumentsDirPath|CacheDirPath|FileModule|ReactNativeClipboard|lastSavedPath/);
assert.doesNotMatch(kotlin, /WRITE_EXTERNAL_STORAGE/);

assert.match(js, /Choose a folder once/);
assert.doesNotMatch(kotlin, /saveWithPicker|chooseDestination|ACTION_CREATE_DOCUMENT|ACTION_OPEN_DOCUMENT\)/);
console.log('PASS: one-time folder selection, direct auto-save, persistent SAF grant, Android context resolution, and native file creation contracts.');
