/**
 * Which anonymous accounts count as abandoned (see purgeInactiveUsers).
 *
 * Every install gets one anonymous account and keeps it. Reinstalls, emulators and
 * the test phones Google Play runs each upload on leave accounts behind that are
 * never used again; nothing else ever removes them.
 */

/** Last time the account was used: the newest of token refresh, sign in, creation. */
function lastUsedMs(user) {
  const m = user.metadata || {};
  return Math.max(0, ...[m.lastRefreshTime, m.lastSignInTime, m.creationTime]
    .map((t) => (t ? Date.parse(t) : 0))
    .filter((t) => Number.isFinite(t)));
}

/**
 * true for an anonymous account not used for [maxAgeMs]. Never for an account
 * with a sign in method (none exist today) or with custom claims (the admins),
 * and never when its dates are unknown.
 */
function isStaleAnonymous(user, nowMs, maxAgeMs) {
  if ((user.providerData || []).length > 0 || user.email || user.phoneNumber) return false;
  if (user.customClaims && Object.keys(user.customClaims).length > 0) return false;
  const last = lastUsedMs(user);
  return last > 0 && nowMs - last > maxAgeMs;
}

module.exports = { lastUsedMs, isStaleAnonymous };
