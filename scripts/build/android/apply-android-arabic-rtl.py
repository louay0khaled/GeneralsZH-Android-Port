#!/usr/bin/env python3
"""Apply the Android Arabic shaping/RTL hook to the legacy sentence renderer.

The Android renderer intentionally remains a per-glyph renderer. This script
makes the small source edit that feeds it a shaped visual UTF-16 string while
keeping all other platforms untouched. It is idempotent so CI/local rebuilds
can call it repeatedly.
"""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
SOURCE = ROOT / "Core/Libraries/Source/WWVegas/WW3D2/render2dsentence.cpp"
MARKER = '#include "arabicrtl.h"'

INCLUDE_OLD = '#include "GXTrace.h"\n#if defined(__ANDROID__)'
INCLUDE_NEW = (
    '#include "GXTrace.h"\n'
    '#if defined(__ANDROID__)\n'
    '#include "arabicrtl.h"\n'
)

CALL_OLD = (
    '    if(Centered && (WrapWidth > 0 || wcschr(text,L\'\\n\')))\n'
    '        Build_Sentence_Centered(text, hkX, hkY);\n'
    '    else\n'
    '        Build_Sentence_Not_Centered(text, hkX, hkY);\n'
)
CALL_NEW = (
    '    // GeneralsX @feature Android Arabic RTL 17/09/2026\n'
    '    // The legacy renderer consumes one WCHAR at a time. Prepare the\n'
    '    // string once so Arabic letters are contextually shaped and the\n'
    '    // visual RTL order is established before layout/texture generation.\n'
    '    const WCHAR *prepared_text = GeneralsX_Prepare_Arabic_RTL(text);\n'
    '    if (prepared_text == nullptr) {\n'
    '        prepared_text = text;\n'
    }\n\n'
    '    if (Centered && (WrapWidth > 0 || wcschr(prepared_text,L\'\\n\')))\n'
    '        Build_Sentence_Centered(prepared_text, hkX, hkY);\n'
    '    else\n'
    '        Build_Sentence_Not_Centered(prepared_text, hkX, hkY);\n'
)


def main() -> int:
    source = SOURCE.read_text(encoding="utf-8")

    if MARKER in source:
        print(f"Arabic RTL hook already applied: {SOURCE}")
        return 0

    if INCLUDE_OLD not in source:
        raise SystemExit("ERROR: expected Android include anchor not found; source changed upstream")
    if source.count(CALL_OLD) != 1:
        raise SystemExit("ERROR: expected exactly one Build_Sentence call block")

    source = source.replace(INCLUDE_OLD, INCLUDE_NEW, 1)
    source = source.replace(CALL_OLD, CALL_NEW, 1)
    SOURCE.write_text(source, encoding="utf-8", newline="")

    print(f"Applied Android Arabic RTL hook: {SOURCE}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
