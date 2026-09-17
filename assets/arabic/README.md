# Arabic contribution package

This directory documents the contributor-supplied Arabic runtime assets.

The accompanying source archive contains:

- `generals.str` — intended repository path: `languages/arabic/generals.str`
- `arial.ttf` — intended Android runtime path: `fonts/arial.ttf`

The native Android font resolver searches `fonts/<normalized-name>.ttf` and falls back to `fonts/arial.ttf`.

For review, the translation should remain a normal UTF-8 `.str` file in `languages/arabic/`. The font is a binary asset and should only be committed after its redistribution rights are confirmed by the maintainers.

See `docs/WORKDIR/ARABIC_LOCALIZATION_SUBMISSION.md` for checksums, packaging notes, RTL integration notes, and the GeneralsOnline HTTP 403 report.
