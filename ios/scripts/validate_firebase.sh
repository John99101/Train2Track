#!/bin/sh
# Runs for both Organizer and Fastlane builds, before the plist is bundled.
set -eu

plist="${1:-${SRCROOT}/MVVTracker/GoogleService-Info.plist}"
fail() { echo "error: $*" >&2; exit 1; }

/usr/bin/plutil -lint "$plist" >/dev/null || fail "Missing or invalid GoogleService-Info.plist"
project=$(/usr/bin/plutil -extract PROJECT_ID raw "$plist")
bundle=$(/usr/bin/plutil -extract BUNDLE_ID raw "$plist")
[ "$bundle" = "com.mvvtracker.app" ] || fail "Firebase BUNDLE_ID must be com.mvvtracker.app"
[ "${PRODUCT_BUNDLE_IDENTIFIER:-com.mvvtracker.app}" = "$bundle" ] || fail "App and Firebase bundle identifiers differ"

# The simulator lane can create this explicit compilation-only placeholder.
if [ "$project" = "demo-t2t" ] && [ "${CONFIGURATION:-Release}" = "Debug" ] && [ "${PLATFORM_NAME:-}" = "iphonesimulator" ]; then
    echo "warning: Firebase placeholder: compilation only; backend checks are unavailable"
else
    [ "$project" = "your-firebase-project-id" ] || fail "Firebase PROJECT_ID must be your-firebase-project-id; refusing to build against another project"
fi

if [ "${MARKETING_VERSION:-}" = "0.6.0" ]; then
    [ "${CURRENT_PROJECT_VERSION:-}" = "17" ] || fail "Version 0.6.0 must keep build 17"
fi
