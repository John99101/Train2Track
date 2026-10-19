/**
 * Live data per mode group, so an app downloads only what it shows.
 *
 * A city's live snapshot (format v1, see compactLive in transitous.js) holds every
 * vehicle of the region; three quarters of it are buses, and the map shows only
 * S-Bahn, U-Bahn and other trains by default. Each group gets its own snapshot in
 * the same format, with its own lines and stops (indices counted inside the
 * group), built from the same transitous request.
 *
 *   rail  S-Bahn, U-Bahn, regional and long distance trains
 *   tram  trams
 *   bus   buses
 *
 * liveHash says whether a snapshot says anything new: it ignores the base time t
 * (every snapshot gets a new one) and compares the absolute times, so the server
 * can answer "not modified" when nothing changed.
 */
const crypto = require("crypto");

const GROUP_MODES = {
  rail: ["METRO", "SUBWAY", "REGIONAL_RAIL", "REGIONAL_FAST_RAIL", "HIGHSPEED_RAIL", "LONG_DISTANCE", "NIGHT_RAIL", "RAIL"],
  tram: ["TRAM"],
  bus: ["BUS"],
};
const GROUPS = Object.keys(GROUP_MODES);
const GROUP_OF = new Map();
for (const [group, modes] of Object.entries(GROUP_MODES)) for (const m of modes) GROUP_OF.set(m, group);

/** The group a mode belongs to, or null for a mode the apps do not show. */
const groupOf = (mode) => GROUP_OF.get(mode) || null;

/** One snapshot per group ({ rail, tram, bus }), each with its own lines and stops. */
function splitLive(snap) {
  const out = {};
  const lineIdx = {};
  const stopIdx = {};
  for (const g of GROUPS) {
    out[g] = { v: snap.v, city: snap.city, t: snap.t, stale: snap.stale, stops: [], lines: [], segs: [] };
    lineIdx[g] = new Map();
    stopIdx[g] = new Map();
  }
  // The index of row [i] of [source] in the group's own [list] (-1 stays -1).
  const take = (map, list, source, i) => {
    if (!(i >= 0 && i < source.length)) return -1;
    let j = map.get(i);
    if (j === undefined) {
      j = list.length;
      list.push(source[i]);
      map.set(i, j);
    }
    return j;
  };
  for (const s of snap.segs) {
    const line = snap.lines[s[0]];
    const g = line ? groupOf(line[1]) : null;
    if (!g) continue;
    const o = out[g];
    const row = s.slice();
    row[0] = take(lineIdx[g], o.lines, snap.lines, s[0]);
    row[2] = take(stopIdx[g], o.stops, snap.stops, s[2]);
    row[3] = take(stopIdx[g], o.stops, snap.stops, s[3]);
    o.segs.push(row);
  }
  return out;
}

/** A short hash of what a snapshot says, the same as long as nothing changed. */
function liveHash(snap) {
  const segs = snap.segs.map((s) => {
    const r = s.slice();
    r[4] = s[4] + snap.t;
    r[5] = s[5] + snap.t;
    return r;
  });
  return crypto
    .createHash("sha1")
    .update(JSON.stringify([snap.v, snap.city, snap.stale, snap.stops, snap.lines, segs]))
    .digest("base64url")
    .slice(0, 16);
}

module.exports = { GROUPS, GROUP_MODES, groupOf, splitLive, liveHash };
