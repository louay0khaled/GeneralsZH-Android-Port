# Arabic Localization Contribution

This branch contains the Arabic localization contribution prepared for review by the GeneralsX Android maintainers.

## Intended repository layout

- Translation source: `languages/arabic/generals.str`
- Android runtime font: `fonts/arial.ttf`
- Arabic RTL renderer support:
  - `Core/Libraries/Source/WWVegas/WW3D2/arabicrtl.cpp`
  - `Core/Libraries/Source/WWVegas/WW3D2/arabicrtl.h`
  - `scripts/build/android/apply-android-arabic-rtl.py`

The existing language-pack contract in `languages/README.md` requires UTF-8 text packs at `languages/<language>/generals.str`.

On Android, the native FreeType font resolver looks for `fonts/<fontname>.ttf` and falls back to `fonts/arial.ttf`. The current Android packaging script stages that runtime font into `android/app/src/main/assets/gamedata/fonts/`.

## Submitted files

The contributor-supplied archive contains these exact files:

| File | Size | SHA-256 |
| --- | ---: | --- |
| `generals.str` | 368,828 bytes | `a1a50544d0e98128b560d0695d5568c4780dafcde5779507ebb8ebee7b8d5d49` |
| `arial.ttf` | 2,016,024 bytes | `1316eb86592e9abf80866fe3fac801f7f3b1a2d6d070290df1674ee68822013a` |

The translation contains 3,991 structured entries and validates as UTF-8 with no structural parser errors in the submitted archive.

## Maintainer review notes

1. The translation should be copied/committed as `languages/arabic/generals.str` so it remains reviewable as a normal text diff.
2. The font should be installed at runtime as `fonts/arial.ttf` for this Arabic build, because the current Android resolver uses that fallback path.
3. The current packaging script obtains its standard `arial.ttf` from the reproducible Liberation-font staging script. Replacing that runtime asset with the contributor font should be an explicit maintainer-reviewed packaging change rather than silently changing the existing staging source.
4. The supplied font is `iPhone-BoHasssoN`. Public font indexes currently describe it as free for personal use, so redistribution inside a GPL project should be reviewed by the maintainers before the binary is committed or shipped. See the upstream source/licensing references noted in the pull-request discussion.
5. The Arabic RTL implementation already present in this fork is a custom shaping/reordering implementation. It should be tested against mixed Arabic/Latin text, numbers, punctuation, and multiline UI before it is treated as production-ready.

## Authentication blocker

The Android Setup application currently reports HTTP 403 from both:

- `api.playgenerals.online`
- `api-ru.playgenerals.online`

with:

```
{"result":2,"session_token":"","refresh_token":"","user_id":-1,"display_name":"","ban_reason":"","ws_uri":""}
```

This contribution does not attempt to bypass authentication or anti-cheat controls. The requested maintainer guidance is the currently supported Android/third-party authentication flow and, where appropriate, a test GeneralsX server configuration.
