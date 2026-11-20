"use strict";
const $ = id => document.getElementById(id), v = $("v"); let url = null, pv = null;
$("f").onchange = e => {
  const f = e.target.files[0]; if (!f) return; if (url) URL.revokeObjectURL(url); url = URL.createObjectURL(f);
  $("fn").textContent = " " + f.name; v.hidden = false; v.src = url; $("res").hidden = true; $("an").disabled = true; $("ast").textContent = "Loading...";
  v.onloadedmetadata = () => { $("ast").textContent = `Length ${v.duration.toFixed(1)} s.`; $("an").disabled = false; };
  v.onerror = () => { $("ast").textContent = "This browser can't play that file. Try a different format (MP4 or WebM)."; };
};
function setSpan(a, b) { $("s").value = (a / 1000).toFixed(2); $("e").value = (b / 1000).toFixed(2); }
$("an").onclick = async () => {
  $("an").disabled = true; v.pause(); $("res").hidden = true;
  try {
    const r = await BidikLoop.analyze(v, +$("mt").value / 100, +$("mb").value / 100, (d, t) => { $("ast").textContent = `Sampling frames ${d} of ${t}...`; });
    $("res").hidden = false; $("cands").replaceChildren(); $("out").replaceChildren(); $("xst").textContent = "";
    const list = r.candidates.length ? r.candidates : (r.bestEffort ? [r.bestEffort] : []);
    for (const c of list) { const b = document.createElement("button"); b.className = "alt"; b.textContent = `${(c.start / 1000).toFixed(2)}-${(c.end / 1000).toFixed(2)} s (${((c.end - c.start) / 1000).toFixed(2)} s, seam ${(c.seam * 100).toFixed(1)}%)`; b.onclick = () => setSpan(c.start, c.end); $("cands").appendChild(b); }
    if (r.candidates.length) { setSpan(r.candidates[0].start, r.candidates[0].end); $("note").textContent = "Found " + r.candidates.length + " candidate loop(s). The first is selected. A seam near 100% means the last frame matches the first."; }
    else if (r.bestEffort) { setSpan(r.bestEffort.start, r.bestEffort.end); $("note").textContent = "No span passed the seam check. This is the closest pair, shown as a best effort; it may jump at the seam. Trim by hand if you prefer."; }
    else { setSpan(0, Math.min(v.duration * 1000, 5000)); $("note").textContent = "No loop found: the screen needs to go somewhere and come back, at least 0.4 s long. Trim a span by hand to export anyway."; }
    $("ast").textContent = "Done.";
  } catch (e) { $("ast").textContent = "Failed: " + e.message; }
  $("an").disabled = false;
};
$("pv").onclick = () => { clearInterval(pv); const a = +$("s").value, b = +$("e").value; if (!(b > a)) return; v.currentTime = a; v.play(); pv = setInterval(() => { if (v.currentTime >= b || v.ended) v.currentTime = a; }, 40); v.onpause = () => clearInterval(pv); };
$("ex").onclick = async () => {
  clearInterval(pv); v.pause(); $("ex").disabled = true; $("out").replaceChildren();
  try {
    const r = await BidikLoop.exportGif(v, Math.round(+$("s").value * 1000), Math.round(+$("e").value * 1000), +$("fps").value, +$("mw").value, (d, t) => { $("xst").textContent = `Encoding frame ${d} of ${t}...`; });
    const blob = new Blob([r.bytes], {type: "image/gif"}), u = URL.createObjectURL(blob);
    $("xst").textContent = `${r.frames} frames, ${r.width}x${r.height}, ${(r.bytes.length / 1024).toFixed(0)} KB, ${(r.durationMs / 1000).toFixed(2)} s.`;
    const img = document.createElement("img"); img.src = u; img.alt = "Exported looping GIF"; const a = document.createElement("a"); a.href = u; a.download = "bidik-loop.gif"; a.textContent = "Download GIF";
    $("out").append(img, document.createElement("br"), a);
  } catch (e) { $("xst").textContent = "Export failed: " + e.message; }
  $("ex").disabled = false;
};
