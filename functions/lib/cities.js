/**
 * Supported cities. The ids and centres MUST match GermanCity in the apps
 * (app/.../data/model/GermanCity.kt): the apps request /api/live/{id}.
 * Each city's region is centre ± HALF_LAT / HALF_LON degrees, large enough to
 * cover the whole S-Bahn network around the city (e.g. Munich to Freising,
 * Tutzing, Erding), same as the Feed's city box in the app.
 *
 * A city with hidden: true is prepared for a later release: its live data can be
 * asked for (for testing), but the apps do not show it yet and the weekly route
 * job leaves it out. To make it public, remove the flag here and move it from
 * HIDDEN_CITIES to GERMAN_CITIES in both apps.
 */
const HALF_LAT = 0.35;
const HALF_LON = 0.5;

const CITIES = {
  muenchen: { name: "München", lat: 48.1372, lon: 11.5755 },
  berlin: { name: "Berlin", lat: 52.52, lon: 13.405 },
  hamburg: { name: "Hamburg", lat: 53.5511, lon: 9.9937 },
  frankfurt: { name: "Frankfurt", lat: 50.1109, lon: 8.6821 },
  nuernberg: { name: "Nürnberg", lat: 49.4521, lon: 11.0767 },
  stuttgart: { name: "Stuttgart", lat: 48.7758, lon: 9.1829 },
  koeln: { name: "Köln", lat: 50.9375, lon: 6.9603 },
  duesseldorf: { name: "Düsseldorf", lat: 51.2277, lon: 6.7735 },
  dortmund: { name: "Dortmund", lat: 51.5136, lon: 7.4653 },
  leipzig: { name: "Leipzig", lat: 51.3397, lon: 12.3731 },
  hannover: { name: "Hannover", lat: 52.3759, lon: 9.732 },
};

/** Bounding box of a city's region as transitous min/max ("lat,lon") strings. */
function cityBox(id) {
  const c = CITIES[id];
  return {
    min: `${(c.lat - HALF_LAT).toFixed(4)},${(c.lon - HALF_LON).toFixed(4)}`,
    max: `${(c.lat + HALF_LAT).toFixed(4)},${(c.lon + HALF_LON).toFixed(4)}`,
  };
}

/** True when the point lies in the city's region (a little margin for stations on its edge). */
function inRegion(cityId, lat, lon) {
  const c = CITIES[cityId];
  if (!c || typeof lat !== "number" || typeof lon !== "number") return false;
  return Math.abs(lat - c.lat) <= HALF_LAT + 0.05 && Math.abs(lon - c.lon) <= HALF_LON + 0.05;
}

module.exports = { CITIES, HALF_LAT, HALF_LON, cityBox, inRegion };
