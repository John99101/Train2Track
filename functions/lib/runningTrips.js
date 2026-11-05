/**
 * Is a journey running now? A rider's "on board" report only counts on a journey
 * that runs, with a margin for one that has just ended or is about to leave (see
 * onRiderWrite in index.js). Each journey is asked about once and kept for a while,
 * so many riders on one train cost one question to the timetable service.
 */
const MINUTE = 60 * 1000;

/**
 * [fetchTimes](tripId) gives { startMs, endMs }, null for a journey that does not
 * exist, or throws when it got no answer: then nothing is kept and the check throws.
 */
function createRunningCheck(fetchTimes, { now = Date.now, marginMs = 15 * MINUTE, ttlMs = 10 * MINUTE, max = 1000 } = {}) {
  const kept = new Map();   // tripId -> { at, times }
  return async function runningNow(tripId) {
    let hit = kept.get(tripId);
    if (!hit || now() - hit.at > ttlMs) {
      // The question itself is kept, so riders reporting at the same moment share it.
      const entry = { at: now(), times: fetchTimes(tripId) };
      // shortcut: forgets every journey when full; fine below 1000 reported journeys in 10 minutes per instance, use an LRU above.
      if (kept.size >= max) kept.clear();
      kept.set(tripId, entry);
      entry.times.catch(() => { if (kept.get(tripId) === entry) kept.delete(tripId); });
      hit = entry;
    }
    const t = await hit.times;
    return t !== null && now() >= t.startMs - marginMs && now() <= t.endMs + marginMs;
  };
}

module.exports = { createRunningCheck };
