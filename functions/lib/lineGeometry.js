/**
 * Geometry for the line routes: is a stretch of track already part of a line, and
 * which parts of a journey's route a line does not have yet.
 *
 * A line was once built from the route of a single journey. A line with branches
 * (S2 to Petershausen and to Altomünster) or with short runs then only had the part
 * of that one journey, and every vehicle on the other branch floated beside it.
 * Now a line is a list of pieces: the first journey's whole route, then only the
 * parts other journeys add (a branch, a loop), so the set grows by little.
 *
 * Also the station ids the apps open the departures with (stationId).
 *
 * Points are [lat, lon]. Distances in metres, measured on a flat map around the
 * point: exact enough over the few kilometres of a city.
 */
const polyline = require("./polyline");

const M_PER_DEG_LAT = 111320;
// A journey's point closer than this to a line lies on it. Wide enough for the two
// tracks of a line and for a bus whose two directions use the two sides of a road.
const COVER_M = 80;
// A part a journey adds must be at least this long: shorter differences (a turning
// loop, another platform track) would only add data.
const MIN_PIECE_M = 150;
// Long straight stretches are checked every this many metres, not only at their ends.
const SAMPLE_M = 60;
const MAX_PIECES = 16;
const SIMPLIFY_DEG = 0.00003;   // ~3 m, the same as the routes

/** A line's pieces prepared for distance checks (each with its bounding box). */
function lineIndex(pieces) {
  return pieces
    .filter((pts) => pts.length >= 2)
    .map((pts) => {
      let minLat = Infinity, maxLat = -Infinity, minLon = Infinity, maxLon = -Infinity;
      for (const [la, lo] of pts) {
        if (la < minLat) minLat = la;
        if (la > maxLat) maxLat = la;
        if (lo < minLon) minLon = lo;
        if (lo > maxLon) maxLon = lo;
      }
      return { pts, minLat, maxLat, minLon, maxLon };
    });
}

/** Metres from the point to the segment a..b. */
function segmentDistanceM(lat, lon, a, b) {
  const kx = M_PER_DEG_LAT * Math.cos((lat * Math.PI) / 180);
  const ax = (a[1] - lon) * kx, ay = (a[0] - lat) * M_PER_DEG_LAT;
  const bx = (b[1] - lon) * kx, by = (b[0] - lat) * M_PER_DEG_LAT;
  const dx = bx - ax, dy = by - ay;
  const len2 = dx * dx + dy * dy;
  let t = len2 > 0 ? -(ax * dx + ay * dy) / len2 : 0;
  if (t < 0) t = 0; else if (t > 1) t = 1;
  const x = ax + t * dx, y = ay + t * dy;
  return Math.sqrt(x * x + y * y);
}

/** Whether the point lies within [maxM] metres of the line. */
function isCovered(index, lat, lon, maxM = COVER_M) {
  const padLat = maxM / M_PER_DEG_LAT;
  const padLon = maxM / (M_PER_DEG_LAT * Math.cos((lat * Math.PI) / 180));
  for (const p of index) {
    if (lat < p.minLat - padLat || lat > p.maxLat + padLat || lon < p.minLon - padLon || lon > p.maxLon + padLon) continue;
    const pts = p.pts;
    for (let i = 1; i < pts.length; i++) {
      if (segmentDistanceM(lat, lon, pts[i - 1], pts[i]) <= maxM) return true;
    }
  }
  return false;
}

function distanceM(a, b) {
  const kx = M_PER_DEG_LAT * Math.cos((a[0] * Math.PI) / 180);
  const dx = (b[1] - a[1]) * kx, dy = (b[0] - a[0]) * M_PER_DEG_LAT;
  return Math.sqrt(dx * dx + dy * dy);
}

/** The points with extra points on long straight stretches, every SAMPLE_M metres. */
function densify(points) {
  const out = [];
  for (let i = 0; i < points.length; i++) {
    if (i > 0) {
      const a = points[i - 1], b = points[i];
      const steps = Math.floor(distanceM(a, b) / SAMPLE_M);
      for (let s = 1; s < steps; s++) {
        const t = s / steps;
        out.push([a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t]);
      }
    }
    out.push(points[i]);
  }
  return out;
}

function lengthM(points) {
  let m = 0;
  for (let i = 1; i < points.length; i++) m += distanceM(points[i - 1], points[i]);
  return m;
}

/**
 * The parts of [points] (a journey's route) the line does not have yet, each long
 * enough to matter and joined to the line by its neighbouring point.
 */
function missingParts(index, points) {
  const dense = densify(points);
  const covered = dense.map(([la, lo]) => isCovered(index, la, lo));
  const parts = [];
  let i = 0;
  while (i < dense.length) {
    if (covered[i]) { i++; continue; }
    let j = i;
    while (j + 1 < dense.length && !covered[j + 1]) j++;
    // One covered point on each side, so the part meets the line.
    const part = dense.slice(Math.max(0, i - 1), Math.min(dense.length, j + 2));
    if (part.length >= 2 && lengthM(part) >= MIN_PIECE_M) parts.push(polyline.simplify(part, SIMPLIFY_DEG));
    i = j + 1;
  }
  return parts;
}

/**
 * Adds a journey's route to a line row [label, mode, colour, piece, piece, ...]
 * (encoded precision 5 polylines). A new line gets the whole route; a known line
 * only the parts it lacks. Returns the number of pieces added.
 */
function extendRow(row, points) {
  if (points.length < 2) return 0;
  if (row.length < 4) {
    row.push(polyline.encode(polyline.simplify(points, SIMPLIFY_DEG), 5));
    return 1;
  }
  const index = lineIndex(row.slice(3).map((p) => polyline.decode(p, 5)));
  let added = 0;
  for (const part of missingParts(index, points)) {
    if (row.length - 3 >= MAX_PIECES) break;
    row.push(polyline.encode(part, 5));
    added++;
  }
  return added;
}

/**
 * Whether a running vehicle's current hop (its track between two stops) lies on
 * the line: false when a noticeable part of it is away from every piece.
 */
function hopOnLine(index, hopPoints) {
  if (!index.length) return false;
  const dense = densify(hopPoints);
  if (dense.length < 2) return true;
  let away = 0;
  for (const [la, lo] of dense) if (!isCovered(index, la, lo)) away++;
  return away / dense.length < 0.3 || lengthM(hopPoints) * (away / dense.length) < MIN_PIECE_M;
}

/**
 * The id the apps open a station's departures with: the whole station, not one
 * platform. German stops carry IFOPT ids ("de:09162:2:41:81" = district 09162,
 * station 2, area 41, platform 81), so the station is the first three parts. Other
 * ids fall back to the parent the timetable names, else to the stop itself.
 */
function stationId(stop) {
  const m = /^(.*_)?([a-z]{2}:\d+:[^:_]+)/i.exec(stop.stopId || "");
  if (m) return (m[1] || "") + m[2];
  return stop.parentId || stop.stopId || null;
}

module.exports = {
  COVER_M, MIN_PIECE_M, MAX_PIECES,
  lineIndex, isCovered, missingParts, extendRow, hopOnLine, stationId, distanceM,
};
