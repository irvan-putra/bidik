"use strict";
/* JavaScript re-implementation of Bidik's LoopFinder / LoopAnalyzer / GifEncoder / GifExporter
   (app/src/main/java/com/vanipy/bidik/). Same constants and steps; not verified to match the Android build. */
const GRID_W = 64, GRID_H = 36, COARSE_FPS = 5, FINE_FPS = 20, MAX_COARSE_FRAMES = 400, REFINE_WINDOW_MS = 400;
const MIN_LOOP_MS = 400, RANK_SEAM_EPSILON = 0.005, CONTINUATION_TOLERANCE = 0.05, DENSITY_STEP = 4, DENSITY_MIN_STEPS = 3;
const MAX_EXPORT_MS = 20000;

/* ---------- LoopFinder ---------- */
function simW(a, b, w) {
  const top = Math.max(a.skipTop, b.skipTop), bottom = Math.min(GRID_H - a.skipBottom, GRID_H - b.skipBottom);
  if (bottom <= top) return 0;
  let sum = 0;
  for (let row = top; row < bottom; row++) for (let col = 0; col < GRID_W; col++) { const i = row * GRID_W + col; sum += w[i] * Math.abs(a.luma[i] - b.luma[i]); }
  return 1 - sum / 255;
}
function activityWeights(frames) {
  if (frames.length < 2) return null;
  const f = frames[0], top = f.skipTop, bottom = GRID_H - f.skipBottom;
  if (bottom <= top) return null;
  const density = new Int32Array(GRID_W * GRID_H);
  for (let k = 0; k < frames.length - 1; k++) { const a = frames[k], b = frames[k + 1];
    for (let row = top; row < bottom; row++) for (let col = 0; col < GRID_W; col++) { const i = row * GRID_W + col; if (Math.abs(a.luma[i] - b.luma[i]) >= DENSITY_STEP) density[i]++; } }
  const act = new Float64Array(GRID_W * GRID_H); let total = 0;
  for (let i = 0; i < act.length; i++) if (density[i] >= DENSITY_MIN_STEPS) { act[i] = density[i]; total += act[i]; }
  if (total <= 0) return null;
  for (let i = 0; i < act.length; i++) act[i] /= total;
  return act;
}
function motionEvents(frames, from, to, min, w) { let ev = 0, last = from; for (let k = from + 1; k <= to; k++) if (1 - simW(frames[last], frames[k], w) >= min) { ev++; last = k; } return ev; }
function departure(frames, from, to, w) { let worst = 0; for (let k = from + 1; k < to; k++) { const d = 1 - simW(frames[from], frames[k], w); if (d > worst) worst = d; } return worst; }
const hasCycleMotion = (fr, from, to, min, w) => departure(fr, from, to, w) >= min && motionEvents(fr, from, to, min, w) >= 2;
function returnsToStart(fr, from, to, w, seam) { const dep = departure(fr, from, to, w); if (dep <= 0) return false; return (1 - seam) <= 0.25 * dep; }
function indexOfTime(frames, t) { let best = 0, bd = Infinity; for (let i = 0; i < frames.length; i++) { const d = Math.abs(frames[i].t - t); if (d < bd) { bd = d; best = i; } } return best; }
function continuesAcrossSeam(frames, i, j, w, seam) {
  const span = frames[j].t - frames[i].t, step = Math.min(1000, Math.max(200, Math.trunc(span / 4)));
  const aI = indexOfTime(frames, frames[i].t + step), aJ = indexOfTime(frames, frames[j].t + step);
  if (aI > i && aJ > j) return simW(frames[aI], frames[aJ], w) >= seam - CONTINUATION_TOLERANCE;
  const bI = indexOfTime(frames, frames[i].t - step), bJ = indexOfTime(frames, frames[j].t - step);
  if (bI >= 0 && bI < i && bJ >= i && bJ < j) return simW(frames[bI], frames[bJ], w) >= seam - CONTINUATION_TOLERANCE;
  return true;
}
function overlaps(a, b) { const shared = Math.min(a.end, b.end) - Math.max(a.start, b.start); if (shared <= 0) return false; const shorter = Math.min(a.end - a.start, b.end - b.start); return shared * 2 > shorter; }
function rankCandidates(c, max) {
  const bestSeam = c.reduce((m, x) => Math.max(m, x.seam), 0);
  const sorted = [...c].sort((x, y) => { const gx = x.seam >= bestSeam - RANK_SEAM_EPSILON ? 0 : 1, gy = y.seam >= bestSeam - RANK_SEAM_EPSILON ? 0 : 1; return gx - gy || (x.end - x.start) - (y.end - y.start) || y.seam - x.seam; });
  const picked = []; for (const x of sorted) { if (picked.length >= max) break; if (!picked.some(p => overlaps(p, x))) picked.push(x); } return picked;
}
function findLoops(frames, o = {}) {
  const minLoopMs = o.minLoopMs ?? MIN_LOOP_MS, maxLoopMs = o.maxLoopMs ?? 30000, seamThreshold = o.seamThreshold ?? 0.90, motionMin = o.motionMin ?? 0.02, maxCandidates = o.maxCandidates ?? 3;
  if (frames.length < 3) return {candidates: [], bestEffort: null};
  const w = activityWeights(frames); if (!w) return {candidates: [], bestEffort: null};
  const found = [], dep = []; let best = null;
  for (let i = 0; i < frames.length - 1; i++) { const t0 = frames[i].t; let j = i + 1;
    while (j < frames.length && frames[j].t - t0 <= maxLoopMs) { const span = frames[j].t - t0;
      if (span >= minLoopMs) { const seam = simW(frames[i], frames[j], w);
        if (!best || seam > best.seam || (seam === best.seam && span < best.end - best.start)) best = {start: t0, end: frames[j].t, seam};
        if (seam >= seamThreshold && continuesAcrossSeam(frames, i, j, w, seam) && hasCycleMotion(frames, i, j, motionMin, w) && returnsToStart(frames, i, j, w, seam)) { found.push({start: t0, end: frames[j].t, seam}); dep.push(departure(frames, i, j, w)); } }
      j++; } }
  const maxDep = dep.length ? Math.max(...dep) : 0, eligible = found.filter((_, k) => dep[k] >= 0.8 * maxDep);
  const picked = rankCandidates(eligible, maxCandidates);
  const bestEffort = !picked.length && best && hasCycleMotion(frames, indexOfTime(frames, best.start), indexOfTime(frames, best.end), motionMin, w) ? best : null;
  return {candidates: picked, bestEffort};
}

/* ---------- LoopAnalyzer (frame sampling through <video> + canvas) ---------- */
const gridCanvas = Object.assign(document.createElement("canvas"), {width: GRID_W, height: GRID_H});
const gctx = gridCanvas.getContext("2d", {willReadFrequently: true});
function seekTo(video, ms) {
  return new Promise(res => {
    let fin = false; const done = () => { if (fin) return; fin = true; video.removeEventListener("seeked", onSeeked); res(); };
    const onSeeked = () => { if (video.requestVideoFrameCallback) { video.requestVideoFrameCallback(() => done()); setTimeout(done, 300); } else setTimeout(done, 60); };
    video.addEventListener("seeked", onSeeked); video.currentTime = Math.max(0, ms) / 1000; setTimeout(done, 4000);
  });
}
async function sampleGrid(video, ms) {
  await seekTo(video, ms); gctx.drawImage(video, 0, 0, GRID_W, GRID_H);
  const d = gctx.getImageData(0, 0, GRID_W, GRID_H).data, luma = new Uint8Array(GRID_W * GRID_H);
  for (let i = 0; i < luma.length; i++) luma[i] = Math.trunc((d[i*4] + d[i*4+1] + d[i*4+2]) / 3);
  return luma;
}
async function analyze(video, maskTop = 0, maskBottom = 0, onProgress) {
  const durationMs = Math.round(video.duration * 1000), stepMs = Math.trunc(1000 / COARSE_FPS);
  const count = Math.min(Math.trunc(durationMs / stepMs) + 1, MAX_COARSE_FRAMES), skipTop = Math.trunc(GRID_H * maskTop), skipBottom = Math.trunc(GRID_H * maskBottom);
  const frames = [];
  for (let k = 0; k < count; k++) { const t = k * stepMs; if (t >= durationMs) break; frames.push({t, luma: await sampleGrid(video, t), skipTop, skipBottom}); if (onProgress) onProgress(frames.length, count); }
  const coarse = findLoops(frames), top = coarse.candidates[0]; if (!top) return coarse;
  const w = activityWeights(frames); if (!w) return coarse;
  const refined = await refine(video, top, skipTop, skipBottom, w, durationMs); if (!refined) return coarse;
  return {candidates: rankCandidates([refined, ...coarse.candidates.slice(1)], coarse.candidates.length), bestEffort: null};
}
async function windowGrids(video, center, durationMs) {
  const step = Math.trunc(1000 / FINE_FPS), out = []; let t = Math.max(0, center - REFINE_WINDOW_MS);
  while (t <= center + REFINE_WINDOW_MS && t < durationMs) { out.push([t, await sampleGrid(video, t)]); t += step; }
  return out.length ? out : null;
}
async function refine(video, coarse, skipTop, skipBottom, w, durationMs) {
  const sg = await windowGrids(video, coarse.start, durationMs), eg = await windowGrids(video, coarse.end, durationMs); if (!sg || !eg) return null;
  const fr = (t, luma) => ({t, luma, skipTop, skipBottom}); let bestStart = null, bs = -1;
  for (const [t, luma] of sg) { const s = simW(fr(t, luma), fr(coarse.end, eg[0][1]), w); if (s > bs) { bs = s; bestStart = fr(t, luma); } }
  if (!bestStart) return null; let best = null;
  for (const [t, luma] of eg) { if (t - bestStart.t < MIN_LOOP_MS) continue; const s = simW(bestStart, fr(t, luma), w); if (!best || s > best.seam) best = {start: bestStart.t, end: t, seam: s}; }
  return best;
}

/* ---------- GifEncoder ---------- */
const PALETTE = (() => { const t = new Int32Array(256); let i = 0; for (let r = 0; r < 6; r++) for (let g = 0; g < 6; g++) for (let b = 0; b < 6; b++) t[i++] = ((r * 51) << 16) | ((g * 51) << 8) | (b * 51); return t; })();
const nearest = (r, g, b) => { const c = v => Math.trunc((Math.min(255, Math.max(0, v)) * 5 + 127) / 255); return c(r) * 36 + c(g) * 6 + c(b); };
function mapToPalette(rgba, width, height) {
  const n = width * height, out = new Uint8Array(n), wr = new Int32Array(n), wg = new Int32Array(n), wb = new Int32Array(n);
  for (let i = 0; i < n; i++) { wr[i] = rgba[i*4]; wg[i] = rgba[i*4+1]; wb[i] = rgba[i*4+2]; }
  for (let y = 0; y < height; y++) for (let x = 0; x < width; x++) {
    const i = y * width + x, idx = nearest(wr[i], wg[i], wb[i]); out[i] = idx; const c = PALETTE[idx];
    const er = wr[i] - ((c >> 16) & 255), eg = wg[i] - ((c >> 8) & 255), eb = wb[i] - (c & 255);
    const spread = (j, s) => { if (j < 0 || j >= n) return; wr[j] += Math.trunc(er * s / 16); wg[j] += Math.trunc(eg * s / 16); wb[j] += Math.trunc(eb * s / 16); };
    if (x + 1 < width) spread(i + 1, 7);
    if (y + 1 < height) { if (x > 0) spread(i + width - 1, 3); spread(i + width, 5); if (x + 1 < width) spread(i + width + 1, 1); }
  }
  return out;
}
class Bytes { constructor() { this.a = new Uint8Array(1 << 16); this.n = 0; } w(b) { if (this.n >= this.a.length) { const c = new Uint8Array(this.a.length * 2); c.set(this.a); this.a = c; } this.a[this.n++] = b & 255; } ws(s) { for (const ch of s) this.w(ch.charCodeAt(0)); } arr(u, len) { for (let i = 0; i < len; i++) this.w(u[i]); } done() { return this.a.slice(0, this.n); } }
function writeLzw(out, indices) {
  const MIN = 8, CLEAR = 256, EOI = 257; let dict = new Map(), next = EOI + 1, size = MIN + 1; const block = new Uint8Array(255); let bl = 0, bb = 0, bc = 0;
  const flush = () => { out.w(bl); out.arr(block, bl); bl = 0; };
  const emit = code => { bb |= code << bc; bc += size; while (bc >= 8) { block[bl++] = bb & 255; bb >>>= 8; bc -= 8; if (bl === 255) flush(); } };
  emit(CLEAR); let prefix = indices[0];
  for (let i = 1; i < indices.length; i++) { const k = indices[i], key = prefix * 256 + k, ex = dict.get(key);
    if (ex !== undefined) prefix = ex; else { emit(prefix); dict.set(key, next++); if (next === (1 << size) + 1 && size < 12) size++; if (next >= 4096) { emit(CLEAR); dict = new Map(); next = EOI + 1; size = MIN + 1; } prefix = k; } }
  emit(prefix); emit(EOI); if (bc > 0) block[bl++] = bb & 255; if (bl > 0) flush();
}
function writeGif(width, height, frames, delayCs) {
  const o = new Bytes(); o.ws("GIF89a"); o.w(width); o.w(width >> 8); o.w(height); o.w(height >> 8); o.w(0xF7); o.w(0); o.w(0);
  for (const c of PALETTE) { o.w(c >> 16); o.w(c >> 8); o.w(c); }
  o.w(0x21); o.w(0xFF); o.w(0x0B); o.ws("NETSCAPE2.0"); o.w(3); o.w(1); o.w(0); o.w(0); o.w(0);
  for (const f of frames) { o.w(0x21); o.w(0xF9); o.w(4); o.w(0x04); o.w(delayCs); o.w(delayCs >> 8); o.w(0); o.w(0);
    o.w(0x2C); o.w(0); o.w(0); o.w(0); o.w(0); o.w(width); o.w(width >> 8); o.w(height); o.w(height >> 8); o.w(0); o.w(8); writeLzw(o, f); o.w(0); }
  o.w(0x3B); return o.done();
}
async function exportGif(video, startMs, endMs, fps = 10, maxWidth = 480, onProgress) {
  if (!(startMs >= 0)) throw new Error("start must be 0 s or later");
  if (!(endMs > startMs)) throw new Error("end must be after start");
  if (endMs - startMs > MAX_EXPORT_MS) throw new Error("a GIF export is capped at " + MAX_EXPORT_MS / 1000 + " s");
  const videoMs = Math.round(video.duration * 1000); if (startMs >= videoMs) throw new Error("start is past the end of the recording");
  const stop = Math.min(endMs, videoMs), step = Math.trunc(1000 / fps), total = Math.trunc((stop - startMs + step - 1) / step);
  const vw = video.videoWidth, vh = video.videoHeight, W = Math.min(vw, maxWidth), H = Math.max(1, Math.trunc(vh * W / vw));
  const cv = Object.assign(document.createElement("canvas"), {width: W, height: H}), cx = cv.getContext("2d", {willReadFrequently: true}), frames = [];
  let t = startMs, done = 0;
  while (t < stop) { await seekTo(video, t); cx.drawImage(video, 0, 0, W, H); frames.push(mapToPalette(cx.getImageData(0, 0, W, H).data, W, H)); done++; if (onProgress) onProgress(done, total); t += step; }
  if (!frames.length) throw new Error("no frames decoded in the chosen span");
  const bytes = writeGif(W, H, frames, Math.max(1, Math.trunc(100 / fps)));
  return {bytes, frames: frames.length, width: W, height: H, durationMs: stop - startMs};
}
window.BidikLoop = {analyze, exportGif, findLoops, MAX_EXPORT_MS, MIN_LOOP_MS};
