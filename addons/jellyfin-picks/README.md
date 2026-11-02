# Jellyfin Picks

Films and series from your own Jellyfin server, picked for the moment: your watch history, the time of day, the day
of the week and the weather. The [Clean Your TV](../../README.md) launcher shows the picks in its Home spotlight
(`continue_spotlight` with `picks_package`), taking turns with your Continue watching item; **Open** goes to the
title's page in the Jellyfin app. On any other home it is a small app that lists the current picks.

## How it picks

Plain rules on the TV, nothing learned and nothing sent anywhere but your own server:

- **Your taste:** the genres and actors of what you watched, the newest counting most.
- **"Because you watched …":** titles your server finds similar to your last three.
- **The time:** lighter picks in the morning, darker ones late at night; films on weekend evenings, series rather
  than long films on weeknights.
- **The weather:** from the [Weather](../weather/) add-on when it is installed (dramas and mysteries when it rains,
  adventure when it is sunny, thrillers in a storm).
- A little extra for good ratings and new additions, and a little variety through the day.

Only unwatched films and series nobody has started are picked: started ones belong to Continue watching.

## Setup (once, with the remote)

1. In Jellyfin on a phone or computer: **Dashboard → API Keys → +**, name it "TV picks".
2. On the TV, open **Jellyfin Picks**: choose your server (found on the home network; or type its address), type the
   key, choose whose history counts.
3. In your theme: `"continue_spotlight": true` and `"picks_package": "io.github.zendeveloper7.picks"`.

After that it runs by itself: new picks every hour while the TV is on, and at once when Home opens after the TV was
off or when the weather has turned.

## What it sends where

| To | What | When |
|---|---|---|
| Your home network (UDP broadcast, port 7359) | "who is JellyfinServer?" | Setup only, to find the server |
| Your Jellyfin server | The API key; requests for the users, one user's watch history, the unwatched films and series, similar titles, and pictures | Setup, then about once an hour while the TV is on |

Nothing else: no other host, no account, no analytics. The key, the server address and the picks stay in the app's
own storage (backups off). Cleartext HTTP is allowed because most home servers have no certificate; use an `https://`
address if yours has one.

## Permissions

| Permission | Why |
|---|---|
| `INTERNET` | Your Jellyfin server on the home network |
| `RECEIVE_BOOT_COMPLETED` | Keeps the hourly refresh scheduled after a restart |

## For the launcher: the provider

`content://io.github.zendeveloper7.picks.now/now`, named in the `com.gathin.tvhome.picks` meta-data, returns the picks
best first with the columns `id`, `title`, `subtitle`, `reason`, `label` (the moment, e.g. "Rainy Sunday evening"),
`intent` (an `intent:` URI for the Jellyfin app's `StartupActivity` with `ItemId`), `poster` (a `content://` picture or
empty) and `updated`. Read columns by name. A new set is announced with `notifyChange` on that URI.

## Build and install

JDK 17 and Android SDK platform 34, as for the launcher:

```bash
cd addons/jellyfin-picks && ./gradlew assembleRelease
TV_HOST=<tv ip> ../../tools/venv/bin/python -I ../../tools/tv.py --push app/build/outputs/apk/release/app-release.apk /data/local/tmp/addon.apk
TV_HOST=<tv ip> ../../tools/venv/bin/python -I ../../tools/tv.py 'pm install -r /data/local/tmp/addon.apk; rm -f /data/local/tmp/addon.apk'
```

## Tested on

Android TV 12 emulator against a stand-in server that answers the same API calls (setup, a wrong key, picks with
reasons, the spotlight, Open). Not yet on a real Jellyfin server.
