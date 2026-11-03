(() => {
  'use strict';

  const IS_VOICE_MESSAGE = 1 << 13;
  const DISCORD_ATTACHMENT_URL = /^https:\/\/(?:cdn\.discordapp\.com|media\.discordapp\.net)\/(?:attachments|ephemeral-attachments)\//i;
  const SAVE_DIRECTORY = 'voice-messages';
  const MAX_AUDIO_BYTES = 50 * 1024 * 1024;

  function errorText(error) {
    if (error && typeof error.message === 'string') return error.message;
    return String(error);
  }

  function getVoiceAttachment(message) {
    if (!message || typeof message.flags !== 'number') return undefined;
    if ((message.flags & IS_VOICE_MESSAGE) === 0) return undefined;
    if (!Array.isArray(message.attachments) || message.attachments.length !== 1) return undefined;
    const attachment = message.attachments[0];
    if (!attachment || typeof attachment.content_type !== 'string') return undefined;
    if (!attachment.content_type.toLowerCase().startsWith('audio/')) return undefined;
    const url = attachment.url || attachment.proxy_url;
    if (typeof url !== 'string' || !DISCORD_ATTACHMENT_URL.test(url)) return undefined;
    return attachment;
  }

  function getMessageFromProps(props) {
    const candidate = props?.message
      ?? props?.messageRecord?.message
      ?? props?.record?.message
      ?? props?.item?.message;
    if (candidate?.message && typeof candidate.flags !== 'number') return candidate.message;
    return candidate;
  }

  function findActionSheet(element, ActionSheet, seen = new Set()) {
    if (!element || typeof element !== 'object' || seen.has(element)) return undefined;
    seen.add(element);
    if (Array.isArray(element)) {
      for (const child of element) {
        const found = findActionSheet(child, ActionSheet, seen);
        if (found) return found;
      }
      return undefined;
    }
    const node = element;
    if (node.type === ActionSheet || node.type?.displayName === 'ActionSheet' || node.type?.name === 'ActionSheet') return node;
    return findActionSheet(node.props?.children, ActionSheet, seen);
  }

  function addToActionSheet(tree, group, ActionSheet) {
    const sheet = findActionSheet(tree, ActionSheet);
    if (!sheet) return false;
    const children = [sheet.props?.children].flat().filter(Boolean);
    children.push(group);
    sheet.props = { ...sheet.props, children };
    return true;
  }

  function getFileName(attachment, messageId) {
    const raw = typeof attachment.filename === 'string' ? attachment.filename : '';
    let name = raw.replace(/[\\/:*?"<>|\u0000-\u001f]/g, '_').trim();
    if (!name) name = \`voice-message-\${messageId || Date.now()}.ogg\`;
    if (!/\.[a-z0-9]{2,8}$/i.test(name)) name += '.ogg';
    const dot = name.lastIndexOf('.');
    const stem = dot > 0 ? name.slice(0, dot) : name;
    const extension = dot > 0 ? name.slice(dot) : '.ogg';
    const suffix = messageId ? String(messageId).replace(/[^a-zA-Z0-9_-]/g, '').slice(-10) : 'audio';
    return \`\${stem}-\${suffix || 'audio'}-\${Date.now()}\${extension}\`;
  }

  async function fetchAudioAsBase64(url, attachment) {
    let response;
    try {
      response = await fetch(url, { method: 'GET' });
    } catch (error) {
      throw new Error(\`Could not contact Discord's attachment server: \${errorText(error)}\`);
    }
    if (!response || !response.ok) {
      const status = response ? \`\${response.status} \${response.statusText || ''}\`.trim() : 'no response';
      throw new Error(\`Discord's attachment server returned \${status}. The voice message link may have expired.\`);
    }
    const contentType = String(response.headers?.get?.('content-type') || attachment.content_type || '').toLowerCase();
    if (contentType && !contentType.startsWith('audio/') && !contentType.includes('application/octet-stream')) {
      throw new Error(\`The attachment server returned an unexpected content type: \${contentType}\`);
    }
    const buffer = await response.arrayBuffer();
    if (!buffer || buffer.byteLength === 0) throw new Error('Discord returned an empty audio file.');
    if (buffer.byteLength > MAX_AUDIO_BYTES) {
      throw new Error(\`The audio file is larger than the \${Math.round(MAX_AUDIO_BYTES / 1024 / 1024)} MB safety limit.\`);
    }
    const bytes = new Uint8Array(buffer);
    let binary = '';
    for (let offset = 0; offset < bytes.length; offset += 0x8000) {
      binary += String.fromCharCode(...bytes.subarray(offset, offset + 0x8000));
    }
    if (typeof btoa !== 'function') throw new Error('This Valenced build does not expose the base64 encoder required to save audio.');
    return btoa(binary);
  }

  async function saveVoiceMessage(files, attachment, messageId) {
    const url = attachment.url || attachment.proxy_url;
    if (!url || !DISCORD_ATTACHMENT_URL.test(url)) {
      throw new Error('The voice attachment URL is missing or unsupported.');
    }
    // Remote CDN downloads use fetch(). readAsset() can be resolved as an Android
    // packaged resource on some builds and fail with Resource id #0x0.
    const base64 = await fetchAudioAsBase64(url, attachment);
    const fileName = getFileName(attachment, messageId);
    const relativePath = \`\${SAVE_DIRECTORY}/\${fileName}\`;
    let savedPath;
    try {
      if (!files || typeof files.writeFile !== 'function') {
        throw new Error('Valenced FileModule.writeFile is unavailable in this build.');
      }
      savedPath = await files.writeFile('documents', relativePath, base64, 'base64');
    } catch (error) {
      throw new Error(\`Audio downloaded, but Android could not write it to Documents: \${errorText(error)}\`);
    }
    if (typeof savedPath !== 'string' || !savedPath) {
      throw new Error('Android did not return a saved file path after writing the audio.');
    }
    return savedPath;
  }

  const options = plugin({
    start({ cleanup, plugin: pluginApi, unscoped }) {
      const { getModules } = unscoped.modules.finders;
      const { withName } = unscoped.modules.finders.filters;
      const { instead } = unscoped.patcher;
      const { ActionSheetActionCreators } = unscoped.discord.actions;
      const { ActionSheet, ActionSheetRow } = unscoped.discord.design.Design;
      const files = unscoped.discord.native.FileModule;
      const { lookupGeneratedIconComponent } = unscoped.utils.discord;
      const { createElement } = unscoped.react.React;

      cleanup(getModules(withName('LongPressMessageActionSheet'), mod => {
        if (typeof mod?.default !== 'function') return;
        cleanup(instead(mod, 'default', (args, Original) => {
          const tree = Reflect.apply(Original, undefined, args);
          try {
            const message = getMessageFromProps(args?.[0]);
            const attachment = getVoiceAttachment(message);
            if (!attachment) return tree;
            const filename = getFileName(attachment, message?.id);
            const group = createElement(
              ActionSheetRow.Group,
              null,
              createElement(ActionSheetRow, {
                label: 'Download Voice Message',
                icon: createElement(ActionSheetRow.Icon, {
                  IconComponent: lookupGeneratedIconComponent('DownloadIcon'),
                }),
                onPress: async () => {
                  try {
                    ActionSheetActionCreators.hideActionSheet();
                    const savedPath = await saveVoiceMessage(files, attachment, message?.id);
                    alert(\`Voice message saved successfully.\\n\\n\${savedPath}\`);
                  } catch (error) {
                    const detail = errorText(error);
                    pluginApi.reportError(\`Voice message download failed: \${detail}\`);
                    alert(\`Could not save \${filename}.\\n\\n\${detail}\\n\\nCheck Valenced Debug Logs for details.\`);
                  }
                },
              }),
            );
            if (!addToActionSheet(tree, group, ActionSheet)) {
              pluginApi.reportError('Voice message was detected, but the long-press action sheet could not be located.');
            }
          } catch (error) {
            pluginApi.reportError(\`Could not add voice message download action: \${errorText(error)}\`);
          }
          return tree;
        }));
      }, { returnNamespace: true }));
    },
  });

  return { default: options };
})()
