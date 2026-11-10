// Microphone recording with the same speech/silence rules as the phone (VoiceActivity.kt):
// learn the room's noise in the first 0.6 s, wait for speech, stop after 2.5 s of quiet.

const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));

const AUDIO = { channelCount: 1, echoCancellation: false, noiseSuppression: true, autoGainControl: true };

/** The chosen mic, or the Windows default if it's unplugged. */
export async function openMic(micId) {
  if (micId) {
    try { return await navigator.mediaDevices.getUserMedia({ audio: { ...AUDIO, deviceId: { exact: micId } } }); }
    catch (e) { if (e.name !== 'OverconstrainedError' && e.name !== 'NotFoundError' && e.name !== 'NotReadableError') throw e; }
  }
  return navigator.mediaDevices.getUserMedia({ audio: AUDIO });
}

export async function listMics() {
  const devs = await navigator.mediaDevices.enumerateDevices();
  return devs.filter((d) => d.kind === 'audioinput' && d.deviceId !== 'default' && d.deviceId !== 'communications')
    .map((d) => ({ id: d.deviceId, name: d.label || 'Microphone' }));
}

/** Live level (0-1) from a mic for the Settings "test" meter. Returns a stop function. */
export async function meter(micId, onLevel) {
  const stream = await openMic(micId);
  const ctx = new AudioContext();
  const an = ctx.createAnalyser(); an.fftSize = 2048;
  ctx.createMediaStreamSource(stream).connect(an);
  const buf = new Float32Array(an.fftSize);
  const label = stream.getAudioTracks()[0]?.label || '';
  const t = setInterval(() => {
    an.getFloatTimeDomainData(buf);
    let m = 0; for (let i = 0; i < buf.length; i++) m = Math.max(m, Math.abs(buf[i]));
    onLevel(Math.min(1, Math.sqrt(m * 32767 / 12000)), label);
  }, 60);
  return () => { clearInterval(t); stream.getTracks().forEach((x) => x.stop()); ctx.close(); };
}

export async function record({ autoStop, onLevel, signal, micId }) {
  const stream = await openMic(micId);
  const micName = stream.getAudioTracks()[0]?.label || '';
  signal.micName = micName;
  // Same as the phone: 16 kHz mono, uncompressed, so Whisper gets every consonant (no Opus artefacts).
  const ctx = new AudioContext({ sampleRate: 16000 });
  const src = ctx.createMediaStreamSource(stream);
  const an = ctx.createAnalyser();
  an.fftSize = 512;
  src.connect(an);
  const buf = new Float32Array(an.fftSize);
  const chunks = [];
  const tap = ctx.createScriptProcessor(4096, 1, 1);
  tap.onaudioprocess = (e) => chunks.push(new Float32Array(e.inputBuffer.getChannelData(0)));
  const mute = ctx.createGain(); mute.gain.value = 0;
  src.connect(tap); tap.connect(mute); mute.connect(ctx.destination);
  const t0 = performance.now();

  let peak = 0;
  const sampler = setInterval(() => {
    an.getFloatTimeDomainData(buf);
    let m = 0;
    for (let i = 0; i < buf.length; i++) { const a = Math.abs(buf[i]); if (a > m) m = a; }
    peak = Math.max(peak, m * 32767);
  }, 25);

  const result = await new Promise((resolve) => {
    const floorSamples = [];
    let startT = 0, keepT = 0, loudRun = 0, speaking = false, lastLoud = 0, loudest = 0;
    const finish = (why) => { clearInterval(tick); resolve(why); };
    signal.stop = () => finish('stop');
    signal.cancel = () => finish('cancel');
    const tick = setInterval(() => {
      const lvl = peak; peak = 0;
      const el = performance.now() - t0;
      loudest = Math.max(loudest, lvl);
      onLevel?.(Math.min(1, Math.sqrt(lvl / 12000)), speaking);
      if (el < 600) { floorSamples.push(lvl); return; }
      if (!startT) {
        const s = [...floorSamples].sort((a, b) => a - b);
        const floor = s[Math.floor(s.length / 2)] || 100;
        startT = clamp(floor * 2.5, 250, 2500);
        keepT = clamp(floor * 1.5, 150, 1200);
      }
      if (!speaking) {
        loudRun = lvl > startT ? loudRun + 1 : 0;
        if (loudRun >= 3) { speaking = true; lastLoud = el; }
      } else if (lvl > keepT) lastLoud = el;
      if (el > 90000) return finish('stop');
      if (!autoStop) return;
      if (speaking && el > 3000 && el - lastLoud > 2500) return finish('stop');
      if (!speaking && el > 10000) return finish(loudest < 150 ? 'silent' : 'stop');
    }, 100);
  });

  clearInterval(sampler);
  const duration = performance.now() - t0;
  tap.disconnect(); src.disconnect();
  stream.getTracks().forEach((t) => t.stop());
  ctx.close();
  if (result === 'cancel') return { cancelled: true };
  if (result === 'silent') return { silent: true };
  if (duration < 500) return { tooShort: true };
  const { wav, meta } = toWav(chunks, ctx.sampleRate);
  return { audio: wav, mime: 'audio/wav', meta: { ...meta, mic: micName } };
}

/**
 * Boosts quiet speech the way the phone does: the loudest 0.1% of samples is lifted to 70% of full scale,
 * at most 24x, so soft or distant speech reaches Whisper at a normal level. Then 16-bit WAV.
 */
export function toWav(chunks, rate) {
  const n = chunks.reduce((a, c) => a + c.length, 0);
  const pcm = new Float32Array(n);
  let o = 0; for (const c of chunks) { pcm.set(c, o); o += c.length; }
  const step = Math.max(1, Math.floor(n / 20000));
  const sample = []; for (let i = 0; i < n; i += step) sample.push(Math.abs(pcm[i]));
  sample.sort((a, b) => a - b);
  const p999 = sample[Math.floor(sample.length * 0.999)] || 0;
  const gain = p999 > 0 ? clamp(0.7 / p999, 1, 24) : 1;
  const out = new ArrayBuffer(44 + n * 2), v = new DataView(out);
  const str = (off, s) => { for (let i = 0; i < s.length; i++) v.setUint8(off + i, s.charCodeAt(i)); };
  str(0, 'RIFF'); v.setUint32(4, 36 + n * 2, true); str(8, 'WAVE'); str(12, 'fmt ');
  v.setUint32(16, 16, true); v.setUint16(20, 1, true); v.setUint16(22, 1, true); v.setUint32(24, rate, true);
  v.setUint32(28, rate * 2, true); v.setUint16(32, 2, true); v.setUint16(34, 16, true); str(36, 'data'); v.setUint32(40, n * 2, true);
  for (let i = 0; i < n; i++) v.setInt16(44 + i * 2, clamp(pcm[i] * gain, -1, 1) * 32767, true);
  return { wav: out, meta: { seconds: +(n / rate).toFixed(1), level: Math.round(p999 * 100), gain: +gain.toFixed(1), rate } };
}
