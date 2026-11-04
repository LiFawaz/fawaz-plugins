import { callNativeMethod } from '@valenced/modules/native';

const PLUGIN_ID = 'com.lifawaz.voice-message-downloader';
const DEFAULTS = Object.freeze({ folderName: '' });
const VOICE_FLAG = 1 << 13;
const CDN_URL = /^https:\/\/(?:cdn\.discordapp\.com|media\.discordapp\.net)\/(?:attachments|ephemeral-attachments)\//i;

const asError = error => error && typeof error.message === 'string' ? error.message : String(error);

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

  if (node.type === ActionSheet || node.type &&
    (node.type.displayName === 'ActionSheet' || node.type.name === 'ActionSheet')) return node;

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

async function chooseFolder(jsonStorage, notify = true) {
  const folderName = await callNativeMethod(PLUGIN_ID + '.chooseFolder', []);
  if (!folderName) {
    if (notify) alert('Folder selection was cancelled. Nothing was downloaded.');
    return null;
  }

  await jsonStorage.set({ folderName });
  if (notify) alert('Save folder selected:\n\n' + folderName + '\n\nThis folder will be remembered for future voice messages.');
  return folderName;
}

function SettingsComponent({ api }) {
  const stored = Object.assign({}, DEFAULTS, api.jsonStorage.use() || {});
  const folderName = typeof stored.folderName === 'string' && stored.folderName
    ? stored.folderName
    : 'No folder selected';
  const Design = valenced.discord.design.Design;
  const h = valenced.react.React.createElement;

  const choose = async () => {
    try {
      await chooseFolder(api.jsonStorage);
    } catch (error) {
      alert('Could not open Android Files:\n\n' + asError(error));
    }
  };

  return h(Design.Stack, { spacing: 16 },
    h(Design.TableRowGroup, { title: 'Android save folder' },
      h(Design.TableRow, {
        label: 'Choose or change folder',
        subLabel: folderName,
        onPress: choose
      })
    ),
    h(Design.Text, { variant: 'text-sm/normal' },
      'Android Files will open. Select an existing folder, or create a new folder in the picker when your file provider supports it. The selected folder and its write permission are remembered, so future downloads go there automatically.'
    )
  );
}

const options = plugin({
  jsonStorage: { load: true, default: DEFAULTS },

  start({ cleanup, plugin: pluginApi, jsonStorage, unscoped }) {
    callNativeMethod(PLUGIN_ID + '.getFolder', [])
      .then(folderName => {
        if (typeof folderName === 'string' && folderName &&
          (!jsonStorage.cache || jsonStorage.cache.folderName !== folderName)) {
          return jsonStorage.set({ folderName });
        }
      })
      .catch(error => pluginApi.reportError('Could not read the Android save folder: ' + asError(error)));

    const getModules = unscoped.modules.finders.getModules;
    const withName = unscoped.modules.finders.filters.withName;
    const instead = unscoped.patcher.instead;
    const actionCreators = unscoped.discord.actions.ActionSheetActionCreators;
    const Design = unscoped.discord.design.Design;
    const ActionSheet = Design.ActionSheet;
    const ActionSheetRow = Design.ActionSheetRow;
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

                  let folderName = await callNativeMethod(PLUGIN_ID + '.getFolder', []);
                  if (typeof folderName !== 'string' || !folderName) {
                    folderName = await chooseFolder(jsonStorage, false);
                    if (!folderName) {
                      alert('Folder selection was cancelled. Nothing was downloaded.');
                      return;
                    }
                  }

                  const result = await callNativeMethod(PLUGIN_ID + '.save', [
                    attachment.url || attachment.proxy_url,
                    name,
                    attachment.content_type
                  ]);

                  const savedName = result && typeof result.filename === 'string' ? result.filename : name;
                  const savedFolder = result && typeof result.folderName === 'string' ? result.folderName : folderName;
                  await jsonStorage.set({ folderName: savedFolder });
                  alert('Voice message saved successfully.\n\nFolder: ' + savedFolder + '\nFile: ' + savedName);
                } catch (error) {
                  const detail = asError(error);
                  pluginApi.reportError('Voice message download failed: ' + detail);
                  alert('Could not save ' + name + '.\n\n' + detail +
                    '\n\nOpen Configure and choose the Android save folder again if its access was removed.');
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

export default options;

declare module '@valenced/modules/native' {
  export interface NativeMethods {
    'com.lifawaz.voice-message-downloader.chooseFolder': [args: [], returnValue: string | null];
    'com.lifawaz.voice-message-downloader.getFolder': [args: [], returnValue: string | null];
    'com.lifawaz.voice-message-downloader.save': [
      args: [url: string, filename: string, contentType: string],
      returnValue: { filename: string; folderName: string }
    ];
  }
}
