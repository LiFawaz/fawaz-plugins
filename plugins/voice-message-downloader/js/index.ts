declare const plugin: any;
declare const alert: (message: string) => void;

type VoiceAttachment = {
  filename?: string;
  content_type?: string;
  url?: string;
  proxy_url?: string;
};

type VoiceMessage = {
  id?: string;
  flags?: number;
  attachments?: VoiceAttachment[];
  message?: VoiceMessage;
};

type NativeFileModule = {
  readAsset(uri: string, encoding: 'base64' | 'utf8'): Promise<string>;
  fileExists(path: string): Promise<boolean>;
  writeFile(storageDir: 'cache' | 'documents', path: string, data: string, encoding: 'base64' | 'utf8'): Promise<string>;
  getConstants(): { DocumentsDirPath: string; CacheDirPath: string };
};

const IS_VOICE_MESSAGE = 1 << 13;
const DISCORD_ATTACHMENT_URL = /^https:\/\/(?:cdn\.discordapp\.com|media\.discordapp\.net)\/(?:attachments|ephemeral-attachments)\//i;
const SAVE_DIRECTORY = 'voice-messages';

function getVoiceAttachment(message: VoiceMessage | undefined): VoiceAttachment | undefined {
  if (!message || typeof message.flags !== 'number') return undefined;
  if ((message.flags & IS_VOICE_MESSAGE) === 0) return undefined;
  const attachments = message.attachments;
  if (!Array.isArray(attachments) || attachments.length !== 1) return undefined;
  const attachment = attachments[0];
  if (!attachment || typeof attachment.content_type !== 'string') return undefined;
  if (!attachment.content_type.toLowerCase().startsWith('audio/')) return undefined;
  const url = attachment.url || attachment.proxy_url;
  if (typeof url !== 'string' || !DISCORD_ATTACHMENT_URL.test(url)) return undefined;
  return attachment;
}

function getMessageFromProps(props: any): VoiceMessage | undefined {
  const candidate = props?.message ?? props?.messageRecord?.message ?? props?.record?.message ?? props?.item?.message;
  if (candidate?.message && typeof candidate.flags !== 'number') return candidate.message;
  return candidate;
}

function findActionSheet(element: unknown, ActionSheet: unknown, seen = new Set<unknown>()): any | undefined {
  if (!element || typeof element !== 'object' || seen.has(element)) return undefined;
  seen.add(element);
  if (Array.isArray(element)) {
    for (const child of element) {
      const found = findActionSheet(child, ActionSheet, seen);
      if (found) return found;
    }
    return undefined;
  }
  const node = element as any;
  if (node.type === ActionSheet || node.type?.displayName === 'ActionSheet' || node.type?.name === 'ActionSheet') return node;
  return findActionSheet(node.props?.children, ActionSheet, seen);
}

function addToActionSheet(tree: unknown, group: unknown, ActionSheet: unknown): boolean {
  const sheet = findActionSheet(tree, ActionSheet);
  if (!sheet) return false;
  const children = [sheet.props?.children].flat().filter(Boolean);
  children.push(group);
  sheet.props = { ...sheet.props, children };
  return true;
}

function safeFileName(attachment: VoiceAttachment, messageId?: string): string {
  const raw = typeof attachment.filename === 'string' ? attachment.filename : '';
  let name = raw.replace(/[\\/:*?"<>|\u0000-\u001f]/g, '_').trim();
  if (!name) name = \`voice-message-\${messageId || Date.now()}.ogg\`;
  if (!/\.[a-z0-9]{2,8}$/i.test(name)) name += '.ogg';
  return name;
}

async function saveVoiceMessage(files: NativeFileModule, attachment: VoiceAttachment, messageId?: string): Promise<string> {
  const url = attachment.url || attachment.proxy_url;
  if (!url || !DISCORD_ATTACHMENT_URL.test(url)) throw new Error('The voice attachment URL is missing or unsupported.');
  const base64 = await files.readAsset(url, 'base64');
  if (typeof base64 !== 'string' || base64.length < 16) throw new Error('Discord returned an empty audio file.');
  const name = safeFileName(attachment, messageId);
  const { DocumentsDirPath } = files.getConstants();
  const folderPath = \`\${DocumentsDirPath.replace(/\\/+$/, '')}/\${SAVE_DIRECTORY}\`;
  let finalName = name;
  const originalPath = \`\${folderPath}/\${finalName}\`;
  try {
    if (await files.fileExists(originalPath)) {
      const dot = name.lastIndexOf('.');
      const stem = dot > 0 ? name.slice(0, dot) : name;
      const extension = dot > 0 ? name.slice(dot) : '.ogg';
      finalName = \`\${stem}-\${messageId || Date.now()}\${extension}\`;
    }
  } catch {}
  const relativePath = \`\${SAVE_DIRECTORY}/\${finalName}\`;
  const savedPath = await files.writeFile('documents', relativePath, base64, 'base64');
  if (typeof savedPath !== 'string' || !savedPath) throw new Error('Android did not return a saved file path.');
  return savedPath;
}

export default plugin({
  start({ cleanup, plugin, unscoped }) {
    const { getModules } = unscoped.modules.finders;
    const { withName } = unscoped.modules.finders.filters;
    const { instead } = unscoped.patcher;
    const { ActionSheetActionCreators } = unscoped.discord.actions;
    const { ActionSheet, ActionSheetRow } = unscoped.discord.design.Design;
    const files = unscoped.discord.native.FileModule as NativeFileModule;
    const { lookupGeneratedIconComponent } = unscoped.utils.discord;
    const { createElement } = unscoped.react.React;

    cleanup(getModules(withName('LongPressMessageActionSheet'), (mod: any) => {
      if (typeof mod?.default !== 'function') return;
      cleanup(instead(mod, 'default', (args: any[], Original: (...args: any[]) => any) => {
        const tree = Reflect.apply(Original, undefined, args);
        try {
          const message = getMessageFromProps(args?.[0]);
          const attachment = getVoiceAttachment(message);
          if (!attachment) return tree;
          const filename = safeFileName(attachment, message?.id);
          const group = createElement(
            ActionSheetRow.Group,
            null,
            createElement(ActionSheetRow, {
              label: 'Download Voice Message',
              icon: createElement(ActionSheetRow.Icon, {
                IconComponent: lookupGeneratedIconComponent('DownloadIcon'),
              }),
              onPress: async () => {
                ActionSheetActionCreators.hideActionSheet();
                try {
                  const savedPath = await saveVoiceMessage(files, attachment, message?.id);
                  alert(\`Voice message saved successfully.\\n\\n\${savedPath}\`);
                } catch (error) {
                  const detail = error instanceof Error ? error.message : String(error);
                  plugin.reportError(\`Voice message download failed: \${detail}\`);
                  alert(\`Could not save \${filename}.\\n\\n\${detail}\\n\\nCheck Valenced Debug Logs for details.\`);
                }
              },
            }),
          );
          if (!addToActionSheet(tree, group, ActionSheet)) {
            plugin.reportError('Voice message was detected, but the long-press action sheet could not be located.');
          }
        } catch (error) {
          plugin.reportError(\`Could not add voice message download action: \${String(error)}\`);
        }
        return tree;
      }));
    }, { returnNamespace: true }));
  },
});
