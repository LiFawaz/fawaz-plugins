(() => {
  'use strict';

  const DEFAULTS = Object.freeze({ storageDir: 'documents', folder: 'voice-messages' });
  const VOICE_FLAG = 1 << 13;
  const CDN_URL = /^https:\/\/(?:cdn\.discordapp\.com|media\.discordapp\.net)\/(?:attachments|ephemeral-attachments)\//i;
  const MAX_BYTES = 50 * 1024 * 1024;

  const asError = error => error && typeof error.message === 'string' ? error.message : String(error);
  const storageDir = value => value === 'cache' ? 'cache' : 'documents';

  function folderPath(value) {
    const parts = String(value || DEFAULTS.folder)
      .replace(/\\/g, '/')
      .split('/')
      .map(part => part.trim())
      .filter(part => part && part !== '.' && part !== '..')
      .map(part => part.replace(/[^a-zA-Z0-9 _.-]/g, '_').slice(0, 48))
      .filter(part => part && !/^\.+$/.test(part))
      .slice(0, 6);
    return parts.length ? parts.join('/') : DEFAULTS.folder;
  }

  function settingsOf(value) {
    return {
      storageDir: storageDir(value && value.storageDir),
      folder: folderPath(value && value.folder)
    };
  }

  function voiceAttachment(message) {
    if (!message || typeof message.flags !== 'number' || !(message.flags & VOICE_FLAG)) return null;
    if (!Array.isArray(message.attachments) || message.attachments.length !== 1) return null;
    const attachment = message.attachments[0];
    if (!attachment || typeof attachment.content_type !== 'string') return null;
    if (!attachment.content_type.toLowerCase().startsWith('audio/')) return null;
    const url = attachment.url || attachment.proxy_url;
    return typeof url === 'string' && CDN_URL.test(url) ? attachment : null;
  }

  function messageFrom(props) {
    const candidate = props && (props.message || (props.messageRecord && props.messageRecord.message) ||
      (props.record && props.record.message) || (props.item && props.item.message));
    return candidate && candidate.message && typeof candidate.flags !== 'number' ? candidate.message : candidate;
  }

  function findSheet(node, ActionSheet, seen) {
    if (!node || typeof node !== 'object' || seen.has(node)) return null;
    seen.add(node);
    if (Array.isArray(node)) {
      for (const child of node) {
        const found = findSheet(child, ActionSheet, seen);
        if (found) return found;
      }
      return null;
    }
    if (node.type === ActionSheet || node.type && (node.type.displayName === 'ActionSheet' || node.type.name === 'ActionSheet')) return node;
    return findSheet(node.props && node.props.children, ActionSheet, seen);
  }

  function insertAction(tree, group, ActionSheet) {
    const sheet = findSheet(tree, ActionSheet, new Set());
    if (!sheet) return false;
    const children = [sheet.props && sheet.props.children].flat().filter(Boolean);
    children.push(group);
    sheet.props = Object.assign({}, sheet.props, { children });
    return true;
  }

  function filenameFor(attachment, messageId) {
    let name = String(attachment.filename || '').replace(/[\\/:*?"<>|\u0000-\u001f]/g, '_').trim();
    if (!name) name = 'voice-message.ogg';
    if (!/\.[a-z0-9]{2,8}$/i.test(name)) name += '.ogg';
    const dot = name.lastIndexOf('.');
    const stem = dot > 0 ? name.slice(0, dot) : name;
    const ext = dot > 0 ? name.slice(dot) : '.ogg';
    const suffix = messageId ? String(messageId).replace(/[^a-zA-Z0-9_-]/g, '').slice(-10) : 'audio';
    return stem + '-' + (suffix || 'audio') + '-' + Date.now() + ext;
  }

  function toBase64(buffer) {
    const bytes = new Uint8Array(buffer);
    const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
    let output = '';
    for (let i = 0; i < bytes.length; i += 3) {
      const a = bytes[i];
      const hasB = i + 1 < bytes.length;
      const hasC = i + 2 < bytes.length;
      const b = hasB ? bytes[i + 1] : 0;
      const c = hasC ? bytes[i + 2] : 0;
      output += alphabet[a >> 2];
      output += alphabet[((a & 3) << 4) | (b >> 4)];
      output += hasB ? alphabet[((b & 15) << 2) | (c >> 6)] : '=';
      output += hasC ? alphabet[c & 63] : '=';
    }
    return output;
  }

  async function downloadBase64(url, attachment) {
    let response;
    try {
      response = await fetch(url, { method: 'GET' });
    } catch (error) {
      throw new Error("Could not contact Discord's attachment server: " + asError(error));
    }
    if (!response || !response.ok) {
      const status = response ? String(response.status) + ' ' + (response.statusText || '') : 'no response';
      throw new Error("Discord's attachment server returned " + status.trim() + '. The link may have expired.');
    }
    const type = String(response.headers && response.headers.get && response.headers.get('content-type') || attachment.content_type || '').toLowerCase();
    if (type && !type.startsWith('audio/') && !type.includes('application/octet-stream')) {
      throw new Error('Unexpected attachment content type: ' + type);
    }
    const buffer = await response.arrayBuffer();
    if (!buffer || !buffer.byteLength) throw new Error('Discord returned an empty audio file.');
    if (buffer.byteLength > MAX_BYTES) throw new Error('Audio file exceeds the 50 MB safety limit.');
    return toBase64(buffer);
  }

  async function save(files, attachment, messageId, configured) {
    const url = attachment.url || attachment.proxy_url;
    if (!url || !CDN_URL.test(url)) throw new Error('The voice attachment URL is missing or unsupported.');
    const base64 = await downloadBase64(url, attachment);
    const config = settingsOf(configured);
    if (!files || typeof files.writeFile !== 'function') throw new Error('Valenced FileModule.writeFile is unavailable.');
    const relativePath = config.folder + '/' + filenameFor(attachment, messageId);
    let result;
    try {
      result = await files.writeFile(config.storageDir, relativePath, base64, 'base64');
    } catch (error) {
      throw new Error('Audio downloaded, but Android could not write to ' + config.storageDir + ': ' + asError(error));
    }
    if (typeof result !== 'string' || !result) throw new Error('Android did not return the saved file path.');
    return result;
  }

  function SettingsComponent({ api }) {
    const value = settingsOf(Object.assign({}, DEFAULTS, api.jsonStorage.use() || {}));
    const Design = valenced.discord.design.Design;
    const h = valenced.react.React.createElement;
    return h(Design.Stack, { spacing: 20 },
      h(Design.TableRadioGroup, {
        title: 'Save location',
        value: value.storageDir,
        onChange: next => api.jsonStorage.set({ storageDir: storageDir(next) })
      },
        h(Design.TableRadioRow, { label: 'Documents', subLabel: 'Persistent storage inside Valenced.', value: 'documents' }),
        h(Design.TableRadioRow, { label: 'Cache', subLabel: 'Temporary storage that may be cleared.', value: 'cache' })
      ),
      h(Design.TableRowGroup, { title: 'Folder name' },
        h(Design.TextInput, {
          label: 'Folder inside the selected location',
          value: value.folder,
          maxLength: 120,
          placeholder: 'voice-messages',
          description: 'Nested folders are supported, for example Audio/Discord.',
          onChange: folder => api.jsonStorage.set({ folder })
        })
      ),
      h(Design.Text, { variant: 'text-sm/normal' },
        'This saves inside Valenced app storage. The current Valenced file API does not expose the public Android Downloads folder or Android folder picker.'
      )
    );
  }

  const options = plugin({
    jsonStorage: { load: true, default: DEFAULTS },
    start({ cleanup, plugin: pluginApi, jsonStorage, unscoped }) {
      const getModules = unscoped.modules.finders.getModules;
      const withName = unscoped.modules.finders.filters.withName;
      const instead = unscoped.patcher.instead;
      const actionCreators = unscoped.discord.actions.ActionSheetActionCreators;
      const Design = unscoped.discord.design.Design;
      const ActionSheet = Design.ActionSheet;
      const ActionSheetRow = Design.ActionSheetRow;
      const files = unscoped.discord.native.FileModule;
      const icon = unscoped.utils.discord.lookupGeneratedIconComponent;
      const h = unscoped.react.React.createElement;

      cleanup(getModules(withName('LongPressMessageActionSheet'), module => {
        if (!module || typeof module.default !== 'function') return;
        cleanup(instead(module, 'default', (args, original) => {
          const tree = Reflect.apply(original, undefined, args);
          try {
            const message = messageFrom(args && args[0]);
            const attachment = voiceAttachment(message);
            if (!attachment) return tree;

            const name = filenameFor(attachment, message.id);
            const group = h(ActionSheetRow.Group, null,
              h(ActionSheetRow, {
                label: 'Download Voice Message',
                icon: h(ActionSheetRow.Icon, { IconComponent: icon('DownloadIcon') }),
                onPress: async () => {
                  try {
                    actionCreators.hideActionSheet();
                    const configured = settingsOf(jsonStorage.cache || DEFAULTS);
                    const path = await save(files, attachment, message.id, configured);
                    alert('Voice message saved successfully.\\n\\n' + path);
                  } catch (error) {
                    const detail = asError(error);
                    pluginApi.reportError('Voice message download failed: ' + detail);
                    alert('Could not save ' + name + '.\\n\\n' + detail + '\\n\\nCheck Valenced Debug Logs for details.');
                  }
                }
              })
            );
            if (!insertAction(tree, group, ActionSheet)) {
              pluginApi.reportError('Voice message was detected, but the long-press action sheet could not be located.');
            }
          } catch (error) {
            pluginApi.reportError('Could not add voice message download action: ' + asError(error));
          }
          return tree;
        }));
      }, { returnNamespace: true }));
    },
    SettingsComponent
  });

  return { default: options };
})()
