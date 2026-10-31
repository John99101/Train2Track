# Map data

The released apps ship the line routes and station dots of every city in this folder,
built by the live service's own server. They are not part of this repository.

A version built from this code starts without them: the map downloads the lines and
stations from your own server (`/api/routes/{city}` and `/api/stops/{city}`) and keeps
them. The folder stays here so the iOS project, which copies it into the app, builds.
