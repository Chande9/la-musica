<div align="center">
<img src="fastlane/metadata/android/en-US/images/icon.png" width="160" height="160" style="display: block; margin: 0 auto"/>
<h1>La Musica</h1>
<p><strong>Music, but ours.</strong> A Spotify + YouTube Music client that respects your ears and your freedom.</p>

[![GitHub license](https://img.shields.io/badge/license-GPL--3.0-blue?style=for-the-badge)](LICENSE)
[![API](https://img.shields.io/badge/Spotify%20API-free-green?style=for-the-badge)](#spotify-api)
[![Platform](https://img.shields.io/badge/platform-Android-3DDC84?style=for-the-badge)](#)
</div>

---

**La Musica** is a fork of [Meld](https://github.com/FrancescoGrazioso/Meld) maintained with one goal: make it the best possible *daily-driver* music app — fast, honest, and fully under the user's control. No trackers, no dark patterns, no features you didn't ask for.

## What's different from Meld

- 🎚️ **Ported DSP chain**: loudness-normalized gain with anti-click ramping and a true-peak soft-clip limiter, so volume normalization sounds clean instead of crushed
- 🖤 **pureBlack OLED theme** as default — real black, real battery savings on AMOLED
- 🧠 **ListenBrainz scrobbling** wired end-to-end (token injection, provider selection, settings UI)
- 🛠️ Maintenance-first: upstream syncs, build fixes, and a strict no-bloat policy

## Principles

1. **Honesty over hype** — every feature does what it says, nothing phones home
2. **User control** — every optional behavior has a switch, defaults are conservative
3. **Quality of life** — the app must feel better than the official clients, not just different

## Spotify API

This app uses the free [Spotify Web API](https://developer.spotify.com/documentation/web-api) for metadata. You can plug in your own credentials — see the in-app settings.

## Building

```bash
git clone https://github.com/Chande9/la-musica.git
cd la-musica
./gradlew assembleFossDebug
```

Output: `app/build/outputs/apk/foss/debug/`

## Credits

- **[Meld](https://github.com/FrancescoGrazioso/Meld)** by Francesco Grazioso — the foundation this stands on
- **[Metrolist](https://github.com/mostafaalagamy/Metrolist)** and the YouTube Music client community — upstream lineage
- The icon is a hand-drawn sketch by the maintainer

## License

GPL-3.0 — same as the upstream project. Fork freely, contribute back.