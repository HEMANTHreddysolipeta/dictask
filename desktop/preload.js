const { contextBridge, ipcRenderer } = require('electron');

const call = async (ch, ...a) => {
  const r = await ipcRenderer.invoke(ch, ...a);
  if (r.error) throw new Error(r.error);
  return r.ok;
};

contextBridge.exposeInMainWorld('dictask', {
  transcribe: (audio, mime, vocab, meta) => call('transcribe', audio, mime, vocab, meta),
  parse: (body) => call('parse', body),
  getSettings: () => call('settings:get'),
  setSettings: (patch) => call('settings:set', patch),
  notify: (n) => call('notify', n),
  openMain: (extra) => call('open-main', extra),
  hideWidget: () => call('hide-widget'),
  hotkey: () => call('hotkey'),
  peekKey: () => call('peek-key'),
  peekDone: () => call('peek-done'),
  clickThrough: (on) => ipcRenderer.send('click-through', on),
  reportMics: (list) => ipcRenderer.send('mics', list),
  on: (ch, fn) => { if (['dictate', 'settings', 'open', 'peek'].includes(ch)) ipcRenderer.on(ch, (_e, ...a) => fn(...a)); },
});
