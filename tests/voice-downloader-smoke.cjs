const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const script = fs.readFileSync(path.join(__dirname, '../plugins/voice-message-downloader/js/index.js'), 'utf8');
const saved = [];
const alerts = [];
const errors = [];
let moduleRef;
let patch = null;

const ActionSheet = function ActionSheet() {};
const ActionSheetRow = function ActionSheetRow() {};
ActionSheetRow.Group = function Group() {};
ActionSheetRow.Icon = function Icon() {};
const Design = {
  Stack: function Stack() {},
  TableRadioGroup: function TableRadioGroup() {},
  TableRadioRow: function TableRadioRow() {},
  TableRowGroup: function TableRowGroup() {},
  TextInput: function TextInput() {},
  Text: function Text() {},
  ActionSheet,
  ActionSheetRow
};
const createElement = (type, props, ...children) => ({
  type, props: Object.assign({}, props || {}, { children: children.length === 1 ? children[0] : children })
});
const exported = new Function('valenced', 'plugin', 'alert', 'fetch', 'return ' + script)(
  { discord: { design: { Design } }, react: { React: { createElement } } },
  options => options,
  message => alerts.push(message),
  async () => ({
    ok: true, status: 200, statusText: 'OK',
    headers: { get: () => 'audio/ogg' },
    arrayBuffer: async () => Uint8Array.from([0, 1, 2, 253, 254, 255]).buffer
  })
);

assert.equal(typeof exported.default, 'object');
assert.equal(typeof exported.default.start, 'function');
assert.equal(typeof exported.default.SettingsComponent, 'function');
assert.deepEqual(exported.default.jsonStorage.default, { storageDir: 'documents', folder: 'voice-messages' });

function walk(node, out = []) {
  if (!node || typeof node !== 'object') return out;
  if (Array.isArray(node)) { node.forEach(item => walk(item, out)); return out; }
  out.push(node);
  walk(node.props && node.props.children, out);
  return out;
}

const changes = [];
const ui = exported.default.SettingsComponent({
  api: { jsonStorage: { use: () => ({}), set: value => changes.push(value) } }
});
const uiNodes = walk(ui);
const radio = uiNodes.find(node => node.type === Design.TableRadioGroup);
const folder = uiNodes.find(node => node.type === Design.TextInput);
assert.ok(radio && folder, 'Configure exposes storage and folder options');
radio.props.onChange('cache');
folder.props.onChange('Music/Discord');
assert.deepEqual(changes, [{ storageDir: 'cache' }, { folder: 'Music/Discord' }]);

const unscoped = {
  modules: {
    finders: {
      getModules(_filter, callback) {
        moduleRef = { default: () => createElement(ActionSheet, {}, null) };
        callback(moduleRef);
        return () => {};
      },
      filters: { withName: name => name }
    }
  },
  patcher: {
    instead(target, key, callback) {
      const original = target[key];
      target[key] = (...args) => callback(args, original);
      return () => { target[key] = original; };
    }
  },
  discord: {
    actions: { ActionSheetActionCreators: { hideActionSheet() {} } },
    design: { Design },
    native: {
      FileModule: {
        writeFile: async (dir, file, base64, encoding) => {
          saved.push({ dir, file, base64, encoding });
          return '/app/' + dir + '/' + file;
        }
      }
    }
  },
  utils: { discord: { lookupGeneratedIconComponent: () => function Icon() {} } },
  react: { React: { createElement } }
};

exported.default.start({
  cleanup() {},
  jsonStorage: { cache: { storageDir: 'cache', folder: 'Music/Discord' } },
  plugin: { reportError: value => errors.push(value) },
  unscoped
});

const msg = {
  id: '123456789012345678',
  flags: 1 << 13,
  attachments: [{
    filename: 'voice-message.ogg',
    content_type: 'audio/ogg; codecs=opus',
    url: 'https://cdn.discordapp.com/attachments/123/456/voice.ogg?ex=123'
  }]
};
const tree = moduleRef.default({ message: msg });
const action = walk(tree).find(node => node.type === ActionSheetRow);
assert.ok(action, 'voice message long-press action is inserted');

(async () => {
  await action.props.onPress();
  assert.equal(saved.length, 1);
  assert.equal(saved[0].dir, 'cache');
  assert.match(saved[0].file, /^Music\\/Discord\\/voice-message-9012345678-\\d+\\.ogg$/);
  assert.equal(saved[0].encoding, 'base64');
  assert.equal(saved[0].base64, Buffer.from([0, 1, 2, 253, 254, 255]).toString('base64'));
  assert.equal(errors.length, 0);
  assert.match(alerts[0], /Voice message saved successfully/);
  console.log('PASS: loader contract, Configure UI, CDN fetch, base64 conversion and configured storage write.');
})().catch(error => { console.error(error); process.exitCode = 1; });
