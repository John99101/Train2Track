/**
 * Supported cities. The ids and centres MUST match GermanCity in the apps
 * (app/.../data/model/GermanCity.kt): the apps request /api/live/{id}.
 * Each city's region is centre ± HALF_LAT / HALF_LON degrees, large enough to
 * cover the whole S-Bahn network around the city (e.g. Munich to Freising,
 * Tutzing, Erding), same as the Feed's city box in the app.
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
};

/** Bounding box of a city's region as transitous min/max ("lat,lon") strings. */
function cityBox(id) {
  const c = CITIES[id];
  return {
    min: `${(c.lat - HALF_LAT).toFixed(4)},${(c.lon - HALF_LON).toFixed(4)}`,
    max: `${(c.lat + HALF_LAT).toFixed(4)},${(c.lon + HALF_LON).toFixed(4)}`,
  };
}

module.exports = { CITIES, HALF_LAT, HALF_LON, cityBox };
