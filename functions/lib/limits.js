/**
 * The settings that protect the service against misuse, in one place.
 *
 * EXAMPLE VALUES. The live Train2Track service uses its own values, which are not
 * published. Choose your own before you run a service with this code.
 */
const MINUTE = 60 * 1000;
const HOUR = 60 * MINUTE;

/**
 * App Check for the callables: when true, only the real apps can call them,
 * scripts cannot. Switch it on once your apps send valid App Check tokens.
 */
const ENFORCE_APP_CHECK = true;

// Per pseudonymous user: at most `max` entries within `windowMs`.
const LIMITS = {
  report: [{ windowMs: 10 * MINUTE, max: 5 }, { windowMs: HOUR, max: 15 }],
  vote: [{ windowMs: 10 * MINUTE, max: 8 }, { windowMs: HOUR, max: 30 }],
};

// How long riders whose reports on a journey were dismissed stay hidden there.
const DISMISSAL_STICKY_MS = 6 * HOUR;

module.exports = { ENFORCE_APP_CHECK, LIMITS, DISMISSAL_STICKY_MS };
