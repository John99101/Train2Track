/**
 * Google "encoded polyline" helpers (same algorithm as GeoPolyline.kt in the
 * Android app). Points are [lat, lon] pairs.
 */

function decode(encoded, precision = 5) {
  const factor = Math.pow(10, precision);
  const points = [];
  let index = 0;
  let lat = 0;
  let lng = 0;
  while (index < encoded.length) {
    let shift = 0;
    let result = 0;
    let b;
    do {
      b = encoded.charCodeAt(index++) - 63;
      result |= (b & 0x1f) << shift;
      shift += 5;
    } while (b >= 0x20);
    lat += result & 1 ? ~(result >> 1) : result >> 1;
    shift = 0;
    result = 0;
    do {
      b = encoded.charCodeAt(index++) - 63;
      result |= (b & 0x1f) << shift;
      shift += 5;
    } while (b >= 0x20);
    lng += result & 1 ? ~(result >> 1) : result >> 1;
    points.push([lat / factor, lng / factor]);
  }
  return points;
}

function encodeValue(v) {
  let value = v < 0 ? ~(v << 1) : v << 1;
  let out = "";
  while (value >= 0x20) {
    out += String.fromCharCode((0x20 | (value & 0x1f)) + 63);
    value >>= 5;
  }
  return out + String.fromCharCode(value + 63);
}

function encode(points, precision = 5) {
  const factor = Math.pow(10, precision);
  let prevLat = 0;
  let prevLng = 0;
  let out = "";
  for (const [la, lo] of points) {
    const lat = Math.round(la * factor);
    const lng = Math.round(lo * factor);
    out += encodeValue(lat - prevLat) + encodeValue(lng - prevLng);
    prevLat = lat;
    prevLng = lng;
  }
  return out;
}

/** Perpendicular distance (degrees, longitude latitude-corrected) from p to a-b. */
function perpDistanceDeg(p, a, b) {
  const kx = Math.cos(((a[0] + b[0]) / 2) * (Math.PI / 180));
  const ax = a[1] * kx, ay = a[0];
  const bx = b[1] * kx, by = b[0];
  const px = p[1] * kx, py = p[0];
  const dx = bx - ax, dy = by - ay;
  const lenSq = dx * dx + dy * dy;
  if (lenSq === 0) return Math.hypot(px - ax, py - ay);
  const t = Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / lenSq));
  return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
}

/** Ramer-Douglas-Peucker simplification (iterative). */
function simplify(points, toleranceDeg) {
  const n = points.length;
  if (n < 3) return points;
  const keep = new Uint8Array(n);
  keep[0] = 1;
  keep[n - 1] = 1;
  const stack = [[0, n - 1]];
  while (stack.length) {
    const [first, last] = stack.pop();
    let maxDist = 0;
    let idx = -1;
    for (let i = first + 1; i < last; i++) {
      const d = perpDistanceDeg(points[i], points[first], points[last]);
      if (d > maxDist) { maxDist = d; idx = i; }
    }
    if (maxDist > toleranceDeg && idx !== -1) {
      keep[idx] = 1;
      stack.push([first, idx], [idx, last]);
    }
  }
  return points.filter((_, i) => keep[i]);
}

module.exports = { decode, encode, simplify };
