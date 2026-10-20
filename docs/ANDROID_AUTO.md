# Android Auto

Square exposes its media library and playback controls through the existing
Media3 `MediaLibraryService`. Android Auto supplies the car interface.

The car can browse playlists and recent tracks from the source selected on
the phone, Spotify or YouTube Music, and search that source by voice. YouTube
search and recently played do not require a Spotify session. YouTube playlists
require signing in to YouTube Music on the phone. Selecting a track expands
its containing list into the playback queue.

Search results are cached separately for each source. Recent streaming tracks
are filtered to the selected source so changing services does not offer tracks
that the current player cannot play. Changing the source refreshes subscribed
car screens. Selecting a local file from recent tracks builds a local-only
queue, while selecting a streaming track keeps the streaming entries.

## Installing an APK

Open Square on the phone, choose a source and verify playback first. Connect
the phone to Android Auto and look for Square among its media apps.

For a development APK installed outside a trusted store, Android Auto may hide
the app. Google's documented media-app testing procedure is:

1. Open Android Auto settings on the phone.
2. Tap the version information ten times and enable developer mode.
3. Open the developer settings from the overflow menu and allow unknown sources.
4. Reconnect Android Auto and check its launcher customization settings.

See [Google's Android Auto testing guide](https://developer.android.com/training/cars/testing#unknown-sources).

## Checking a build

Use a compatible vehicle or Google's Desktop Head Unit. These checks require
a device and authenticated music accounts where applicable; a successful build
alone does not confirm them.

- Start with Square closed, connect the car, and open its playlists/recent tracks.
- Play a track from the middle of a playlist; verify the next track follows it.
- Check play/pause, next/previous, shuffle, repeat, and a voice search.
- Repeat browsing and search after selecting the other source on the phone.
  YouTube Music must not wait for a Spotify sign-in or return cached Spotify
  search results.
- Enable DJ mode on the phone with crossfade set to six seconds. With multiple
  songs queued and shuffle enabled, verify an early, blended transition and
  check the car's title, artwork and queue follow the new track.
- Check DJ mode with repeat-one, the end-of-song sleep timer, crossfade off,
  songs shorter than 90 seconds and a queue with no following track. These
  should not cause a DJ transition. Pause and resume must preserve the chosen
  point, and enabling the mode after that point must not skip immediately.

DJ mode uses the phone's existing playback engines, so it also runs while the
screen is off and Android Auto controls the session. It is unavailable with
the Spotify Web Playback SDK fallback and the separate local-file player.
