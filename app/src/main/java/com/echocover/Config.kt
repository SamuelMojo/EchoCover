package com.echocover

object Config {
    // From https://developer.spotify.com/dashboard (see README.md).
    const val CLIENT_ID = "YOUR_SPOTIFY_CLIENT_ID"

    // Must match the Redirect URI you add in the Spotify dashboard exactly.
    const val REDIRECT_URI = "echocover://callback"

    const val SCOPES = "user-read-playback-state user-modify-playback-state user-read-recently-played"

    // How long playback can stay paused or stopped before the home screen replaces the cover
    // (0 = straight away).
    const val PAUSED_CLOCK_AFTER_MS = 10 * 1000L

    // Cross-fade between screens (now playing, home screen, F1 dashboard).
    const val FADE_MS = 450L

    // How often to check what's playing (milliseconds).
    const val POLL_MS = 3000L

    // F1 dashboard: address of the f1server (see f1server/README.md), e.g. "http://203.0.113.7:8765".
    // Leave empty to turn the dashboard off.
    const val F1_SERVER = ""

    // Must match F1SERVER_TOKEN on the server, if you set one.
    const val F1_TOKEN = ""

    // How often to refresh the dashboard during a session, and to check for one otherwise.
    const val F1_POLL_MS = 1000L
    const val F1_IDLE_POLL_MS = 30_000L
}
