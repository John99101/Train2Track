<p align="center">
  <img src="docs/images/banner.png" alt="Train2Track: live public transport in Germany" width="720">
</p>

<p align="center">
  <a href="LICENSE"><img alt="Licence: AGPL 3.0" src="https://img.shields.io/badge/licence-AGPL%203.0-b71c1c"></a>
  <img alt="Platforms: Android and iOS" src="https://img.shields.io/badge/platforms-Android%20%7C%20iOS-333333">
  <img alt="Status: closed test" src="https://img.shields.io/badge/status-closed%20test-555555">
</p>

# Train2Track

Train2Track shows public transport in nine German cities as it moves: S-Bahn, U-Bahn,
trams, buses and regional trains, on a map and as a list, with delays and disruption
notices. Riders can also tell each other that a ticket inspection is taking place on a
journey, as a reminder to keep a valid ticket ready.

The app is free, needs no account and works with a random identifier that does not say
who you are. This repository holds the source code of the Android app, the iOS app and
the server.

Website: [train2track.de](https://train2track.de)

<p align="center">
  <img src="docs/images/screenshot_map.png" alt="Map with live vehicles" width="200">
  <img src="docs/images/screenshot_feed.png" alt="Feed of a city" width="200">
  <img src="docs/images/screenshot_report.png" alt="Report screen of a journey" width="200">
  <img src="docs/images/screenshot_sources.png" alt="Data sources and open source" width="200">
</p>

## What the app does

| | |
|---|---|
| **Map** | Every vehicle in view moves along its route. Tap one to open it. |
| **Feed** | All lines of a city as a live list, with search and filters for S-Bahn, U-Bahn, tram, bus and rail. |
| **Delays** | Shown per journey where the data sources provide live data. Otherwise the journey is marked as scheduled. |
| **Disruptions** | Notices from open data, for the selected city. |
| **Reports** | "On board" marks a journey on which a ticket inspection is taking place. "Has left" ends it. Riders can also vote whether a journey is late. |
| **Ticket button** | Opens the ticket page of the local transport operator. |
| **Languages** | English and German, light and dark appearance. |

Cities: München, Berlin, Hamburg, Frankfurt, Nürnberg, Stuttgart, Köln, Düsseldorf and
Dortmund, each with its surrounding area.

Positions are calculated from timetable and live data. They show where a vehicle should
be, not a measured position.

**The app is an information service.** It is not a tool for travelling without a ticket
or for avoiding inspections. You always need a valid ticket.

## Status

Train2Track is in a closed test on Google Play and TestFlight. It is built and run by
one person in his spare time.

## Privacy by design

What the code in this repository does, so that anyone can check it:

- **No account.** Each installation signs in anonymously and gets a random identifier.
  There is no name, mail address or phone number anywhere.
- **Reports do not show who sent them.** A report is stored under a name that is a hash
  of the identifier and the journey. It is different on every journey, so two reports of
  one rider cannot be linked by other apps. See
  [`functions/lib/reportIds.js`](functions/lib/reportIds.js) and
  [`firestore.rules`](firestore.rules).
- **Location stays on the phone.** It only preselects the city and shows your position
  on the map. It is never sent to the server.
- **Nothing optional without consent.** Usage statistics are off until switched on
  (Android only, iOS has none). No advertising is requested before the consent dialog
  has been answered.
- **Delete my data.** One button erases everything on the server that belongs to the
  identifier, and the app continues with a new one. Identifiers that are not used for
  30 days are removed by the server.
- **Statistics without identifier.** The log of reported inspections holds line,
  station, journey, city and time only.

## How it works

```mermaid
flowchart LR
  subgraph Apps
    A[Android app<br>Kotlin, Jetpack Compose]
    I[iOS app<br>Swift, SwiftUI]
  end
  subgraph Server[Server on Firebase]
    H[Live data API<br>Cloud Functions behind Hosting]
    F[(Firestore<br>reports, delay votes, disruptions)]
    C[Cloud Functions<br>spam limit, moderation, cleanup]
  end
  T[Transitous<br>timetable and live data]
  G[gtfs.de<br>disruption notices]
  M[OpenStreetMap tiles<br>Apple Maps on iOS]

  A & I -->|one snapshot per city| H
  A & I <-->|reports and votes| F
  F --- C
  H --> T
  C --> G
  A & I --> M
  A & I -.->|only when the server is down<br>or outside the cities| T
```

- **Live data.** The server asks Transitous once per city and hands every app the same
  compact snapshot. The apps calculate each vehicle's position from the snapshot and the
  track shapes.
- **Line routes.** The server keeps the route of every line. It asks Transitous once
  per line, tops the set up once a week with lines it does not have yet, and fetches a
  missing line when an app first needs it. The apps never ask Transitous for a route.
- **Reports.** The apps write reports and delay votes straight to Firestore. The rules
  in `firestore.rules` decide what an app may write. Cloud Functions apply the spam
  limit and the moderation.
- **Disruptions.** A scheduled function reads the open feed of gtfs.de and stores the
  current notices, so that no phone has to download the whole feed.

## Repository layout

| Path | What |
|---|---|
| `app/` | Android app (Kotlin, Jetpack Compose, osmdroid). Unit tests in `app/src/test`. |
| `ios/` | iOS app (SwiftUI, MapKit). `ios/MVVTrackerCore` is a Swift package with the shared logic and its tests. The project file is generated by XcodeGen from `ios/project.yml`. |
| `functions/` | Cloud Functions (Node.js): the live data API in `lib/liveApi.js` and `lib/transitous.js`, everything else in `index.js`. |
| `firestore.rules` | What an app may read and write. |
| `functions/test/` | Checks the rules and functions on the Firebase emulators. |

Android is the reference. iOS follows it and must behave the same.

## Data sources and credits

Train2Track only exists because of open data and open source software. Thank you.

| Source | Used for | Licence |
|---|---|---|
| [Transitous](https://transitous.org) | Timetable and live data. All of its sources are listed at [transitous.org/sources](https://transitous.org/sources/). | see the sources |
| [DELFI e.V.](https://www.delfi.de/) | German timetable data and live data, through Transitous | CC BY 4.0 and CC BY-SA |
| [gtfs.de](https://gtfs.de/de/realtime/) | Disruption notices | CC BY-SA 4.0 |
| [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors | Map on Android | ODbL |
| Apple Maps | Map on iOS | terms of Apple |
| [MOTIS](https://github.com/motis-project/motis) | The routing software behind Transitous | MIT |

The data is shortened and converted for display. Converted data from sources under a
share alike licence stays under that licence. The providers are not responsible for the
app.

Software used in the apps includes osmdroid, AndroidX and Jetpack Compose, Kotlin,
OkHttp, Retrofit and the Firebase and Google Mobile Ads software of Google. Each has
its own licence.

## Build it yourself

You can build and run your own version. It needs your own Firebase project: the live
Train2Track service is not part of this repository and may not be used by other apps.

**What is not in this repository, on purpose**

- Firebase config files (`google-services.json`, `GoogleService-Info.plist`), signing
  keys and every other credential.
- The legal notice and the privacy policy of the Train2Track service. The files in the
  apps are placeholders. If you publish a version, you are its operator and must write
  your own.
- The limits that protect the live service against misuse. `functions/lib/limits.js`
  holds example values.
- The list of app managers. `functions/lib/admins.js` is empty.
- The advertising ids. The build uses the public test ids of Google.
- The server address. The apps point to `example.invalid`.

**Before you build**

1. Create a Firebase project with Authentication (anonymous sign in), Cloud Firestore,
   Cloud Functions, Hosting and App Check.
2. Copy `.firebaserc.example` to `.firebaserc` and enter your project id.
3. Replace `example.invalid` and `contact@example.invalid` with your own website and
   contact. Search for both: they are in `app/build.gradle.kts`,
   `app/.../util/AppInfo.kt`, `ios/MVVTracker/App/AppInfo.swift`,
   `ios/MVVTracker/Remote/LiveApiClient.swift` and `functions/lib/transitous.js`.
4. Give your version its own name, icon and application id (see [NOTICE.md](NOTICE.md)).
5. Read the [usage policy of Transitous](https://transitous.org/api/) and follow it.
   It asks, among other things, that every request names your app and a contact.

**Server**

```bash
cd functions && npm install && cd ..
firebase deploy --only firestore:rules,firestore:indexes,functions,hosting
```

**Android** (JDK 17, Android Studio)

Put your `google-services.json` into `app/`, then:

```bash
./gradlew :app:assembleDebug
```

**iOS** (Xcode, XcodeGen, iOS 16 or newer)

Put your `GoogleService-Info.plist` into `ios/MVVTracker/`, set your team in
`ios/project.yml`, then:

```bash
cd ios && xcodegen generate && open Train2Track.xcodeproj
```

## Tests

```bash
# Android unit tests
./gradlew :app:testDebugUnitTest

# Shared iOS logic
cd ios/MVVTrackerCore && swift test

# How the server asks Transitous for line routes (no network needed)
node functions/test/routes.test.js

# Rules and functions on the Firebase emulators (needs Java 21 or newer)
firebase emulators:exec --only auth,firestore,functions --project demo-t2t "node functions/test/hidden_names.test.js"
```

## Contributing

Issues are welcome: bugs, wrong data, ideas.

Code is accepted only under strict rules. Read [CONTRIBUTING.md](CONTRIBUTING.md) before
you write any. In short: open an issue first, sign the contributor agreement
([CLA.md](CLA.md)), keep both apps in step, and never change what the apps collect or
send without agreement.

Found a security problem? Do not open an issue. See [SECURITY.md](SECURITY.md).

Everyone taking part is expected to follow the [code of conduct](CODE_OF_CONDUCT.md).

## Licence and name

Copyright (C) 2026 Jonathan Bonetti

The source code is free software under the
[GNU Affero General Public License, version 3](LICENSE). In short: you may use, study,
change and pass on the code. If you publish a changed app or run a changed server for
others, you must publish your source code under the same licence.

The name Train2Track and the logo are **not** part of that licence. A changed version
must use its own name and its own logo. [NOTICE.md](NOTICE.md) has the details and one
additional permission for the software of Google and Apple that the apps link to.

This repository is a published copy of the maintainer's working repository. It is
updated in single commits, so the history here is short.

## Disclaimer

Train2Track is independent. It is not operated by, sponsored by or affiliated with any
transport operator, transport association or public authority. Names of operators and
lines belong to their owners and are used only to describe the service concerned.

The software comes without any warranty. Positions, delays, disruptions and reports can
be late, incomplete or wrong.
