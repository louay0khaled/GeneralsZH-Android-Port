#include "arabicrtl.h"

#if defined(__ANDROID__)

#include <cstdint>
#include <vector>

namespace {

struct ArabicForm {
    uint16_t base;
    uint16_t isolated;
    uint16_t final_form;
    uint16_t initial;
    uint16_t medial;
};

// Unicode Arabic Presentation Forms-B for the standard Arabic alphabet used
// by Generals.  The legacy Android renderer draws one Unicode code point at a
// time and has no shaping engine, so these pre-shaped glyphs provide the
// contextual forms without changing the renderer's texture pipeline.
static const ArabicForm kArabicForms[] = {
    {0x0621, 0xFE80, 0,      0,      0},
    {0x0622, 0xFE81, 0xFE82, 0,      0},
    {0x0623, 0xFE83, 0xFE84, 0,      0},
    {0x0624, 0xFE85, 0xFE86, 0,      0},
    {0x0625, 0xFE87, 0xFE88, 0,      0},
    {0x0626, 0xFE89, 0xFE8A, 0xFE8B, 0xFE8C},
    {0x0627, 0xFE8D, 0xFE8E, 0,      0},
    {0x0628, 0xFE8F, 0xFE90, 0xFE91, 0xFE92},
    {0x0629, 0xFE93, 0xFE94, 0,      0},
    {0x062A, 0xFE95, 0xFE96, 0xFE97, 0xFE98},
    {0x062B, 0xFE99, 0xFE9A, 0xFE9B, 0xFE9C},
    {0x062C, 0xFE9D, 0xFE9E, 0xFE9F, 0xFEA0},
    {0x062D, 0xFEA1, 0xFEA2, 0xFEA3, 0xFEA4},
    {0x062E, 0xFEA5, 0xFEA6, 0xFEA7, 0xFEA8},
    {0x062F, 0xFEA9, 0xFEAA, 0,      0},
    {0x0630, 0xFEAB, 0xFEAC, 0,      0},
    {0x0631, 0xFEAD, 0xFEAE, 0,      0},
    {0x0632, 0xFEAF, 0xFEB0, 0,      0},
    {0x0633, 0xFEB1, 0xFEB2, 0xFEB3, 0xFEB4},
    {0x0634, 0xFEB5, 0xFEB6, 0xFEB7, 0xFEB8},
    {0x0635, 0xFEB9, 0xFEBA, 0xFEBB, 0xFEBC},
    {0x0636, 0xFEBD, 0xFEBE, 0xFEBF, 0xFEC0},
    {0x0637, 0xFEC1, 0xFEC2, 0xFEC3, 0xFEC4},
    {0x0638, 0xFEC5, 0xFEC6, 0xFEC7, 0xFEC8},
    {0x0639, 0xFEC9, 0xFECA, 0xFECB, 0xFECC},
    {0x063A, 0xFECD, 0xFECE, 0xFECF, 0xFED0},
    {0x0641, 0xFED1, 0xFED2, 0xFED3, 0xFED4},
    {0x0642, 0xFED5, 0xFED6, 0xFED7, 0xFED8},
    {0x0643, 0xFED9, 0xFEDA, 0xFEDB, 0xFEDC},
    {0x0644, 0xFEDD, 0xFEDE, 0xFEDF, 0xFEE0},
    {0x0645, 0xFEE1, 0xFEE2, 0xFEE3, 0xFEE4},
    {0x0646, 0xFEE5, 0xFEE6, 0xFEE7, 0xFEE8},
    {0x0647, 0xFEE9, 0xFEEA, 0xFEEB, 0xFEEC},
    {0x0648, 0xFEED, 0xFEEE, 0,      0},
    {0x0649, 0xFEEF, 0xFEF0, 0,      0},
    {0x064A, 0xFEF1, 0xFEF2, 0xFEF3, 0xFEF4},
};

struct Cluster {
    std::vector<WCHAR> glyphs;
    bool arabic;
    bool latin;
};

enum class Direction {
    Ltr,
    Rtl,
    Neutral
};

static const ArabicForm *FindArabicForm(uint16_t ch)
{
    for (const ArabicForm &form : kArabicForms) {
        if (form.base == ch) {
            return &form;
        }
    }
    return nullptr;
}

static bool IsArabicMark(uint16_t ch)
{
    return (ch >= 0x064B && ch <= 0x065F) ||
           ch == 0x0670 ||
           (ch >= 0x06D6 && ch <= 0x06ED) ||
           (ch >= 0x08D3 && ch <= 0x08E1) ||
           (ch >= 0x08E3 && ch <= 0x08FF);
}

static bool IsArabicPresentation(uint16_t ch)
{
    return (ch >= 0xFB50 && ch <= 0xFDFF) ||
           (ch >= 0xFE70 && ch <= 0xFEFF);
}

static bool IsAsciiLtr(uint16_t ch)
{
    return (ch >= 'A' && ch <= 'Z') ||
           (ch >= 'a' && ch <= 'z') ||
           (ch >= '0' && ch <= '9');
}

static bool IsLatinOrCyrillic(uint16_t ch)
{
    return (ch >= 0x0080 && ch <= 0x024F) ||
           (ch >= 0x0370 && ch <= 0x052F);
}

static bool ContainsArabic(const WCHAR *text)
{
    for (const WCHAR *p = text; *p != 0; ++p) {
        uint16_t ch = static_cast<uint16_t>(*p);
        if (FindArabicForm(ch) != nullptr || IsArabicMark(ch) || IsArabicPresentation(ch)) {
            return true;
        }
    }
    return false;
}

static Direction Classify(const Cluster &cluster)
{
    if (cluster.arabic) {
        return Direction::Rtl;
    }
    if (cluster.latin) {
        return Direction::Ltr;
    }
    return Direction::Neutral;
}

static Direction ResolveNeutral(Direction base, Direction previous, Direction next)
{
    if (previous != Direction::Neutral && previous == next) {
        return previous;
    }
    return base;
}

static uint16_t SelectArabicForm(const ArabicForm &current, bool join_prev, bool join_next)
{
    if (join_prev && join_next && current.medial != 0) {
        return current.medial;
    }
    if (join_prev && current.final_form != 0) {
        return current.final_form;
    }
    if (join_next && current.initial != 0) {
        return current.initial;
    }
    return current.isolated;
}

static std::vector<Cluster> ShapeLine(const std::vector<WCHAR> &line)
{
    std::vector<Cluster> clusters;
    clusters.reserve(line.size());

    for (size_t i = 0; i < line.size();) {
        uint16_t ch = static_cast<uint16_t>(line[i]);

        // Keep legacy hot-key pairs together so an authored "&X" remains
        // adjacent when the visual RTL order is built.
        if (ch == '&' && i + 1 < line.size() && line[i + 1] > L' ') {
            Cluster cluster;
            cluster.arabic = false;
            cluster.latin = true;
            cluster.glyphs.push_back(line[i]);
            cluster.glyphs.push_back(line[i + 1]);
            clusters.push_back(cluster);
            i += 2;
            continue;
        }

        const ArabicForm *form = FindArabicForm(ch);
        if (form == nullptr) {
            Cluster cluster;
            cluster.arabic = IsArabicMark(ch) || IsArabicPresentation(ch);
            cluster.latin = IsAsciiLtr(ch) || IsLatinOrCyrillic(ch);
            cluster.glyphs.push_back(line[i]);
            clusters.push_back(cluster);
            ++i;

            // Combining marks stay attached to their base cluster instead of
            // being reversed independently from it.
            while (i < line.size() && IsArabicMark(static_cast<uint16_t>(line[i]))) {
                clusters.back().glyphs.push_back(line[i]);
                ++i;
            }
            continue;
        }

        bool join_prev = false;
        bool join_next = false;

        size_t previous = i;
        while (previous > 0) {
            --previous;
            uint16_t prev_ch = static_cast<uint16_t>(line[previous]);
            if (IsArabicMark(prev_ch)) {
                continue;
            }
            const ArabicForm *prev_form = FindArabicForm(prev_ch);
            if (prev_form != nullptr) {
                join_prev = form->final_form != 0 && prev_form->initial != 0;
            }
            break;
        }

        size_t next = i + 1;
        while (next < line.size() && IsArabicMark(static_cast<uint16_t>(line[next]))) {
            ++next;
        }
        if (next < line.size()) {
            uint16_t next_ch = static_cast<uint16_t>(line[next]);
            const ArabicForm *next_form = FindArabicForm(next_ch);
            if (next_form != nullptr) {
                join_next = form->initial != 0 && next_form->final_form != 0;
            }
        }

        Cluster cluster;
        cluster.arabic = true;
        cluster.latin = false;
        cluster.glyphs.push_back(
            static_cast<WCHAR>(SelectArabicForm(*form, join_prev, join_next)));

        ++i;
        while (i < line.size() && IsArabicMark(static_cast<uint16_t>(line[i]))) {
            cluster.glyphs.push_back(line[i]);
            ++i;
        }
        clusters.push_back(cluster);
    }

    return clusters;
}

static void AppendCluster(std::vector<WCHAR> &out, const Cluster &cluster)
{
    out.insert(out.end(), cluster.glyphs.begin(), cluster.glyphs.end());
}

static void ShapeAndReorderLine(const std::vector<WCHAR> &line, std::vector<WCHAR> &out)
{
    std::vector<Cluster> clusters = ShapeLine(line);
    if (clusters.empty()) {
        return;
    }

    std::vector<Direction> dirs(clusters.size(), Direction::Neutral);
    Direction base = Direction::Ltr;

    // Unicode's paragraph base direction is effectively all we need here:
    // Generals' strings are short UI labels, not arbitrary rich paragraphs.
    for (size_t i = 0; i < clusters.size(); ++i) {
        dirs[i] = Classify(clusters[i]);
        if (dirs[i] == Direction::Rtl) {
            base = Direction::Rtl;
            break;
        }
        if (dirs[i] == Direction::Ltr) {
            base = Direction::Ltr;
            break;
        }
    }

    // Resolve punctuation/space clusters from their surrounding strong text.
    for (size_t i = 0; i < dirs.size(); ++i) {
        if (dirs[i] != Direction::Neutral) {
            continue;
        }

        Direction previous = Direction::Neutral;
        for (size_t p = i; p > 0; --p) {
            if (dirs[p - 1] != Direction::Neutral) {
                previous = dirs[p - 1];
                break;
            }
        }

        Direction next = Direction::Neutral;
        for (size_t n = i + 1; n < dirs.size(); ++n) {
            if (dirs[n] != Direction::Neutral) {
                next = dirs[n];
                break;
            }
        }

        dirs[i] = ResolveNeutral(base, previous, next);
    }

    struct Run {
        size_t begin;
        size_t end;
        Direction dir;
    };

    std::vector<Run> runs;
    size_t begin = 0;
    while (begin < clusters.size()) {
        size_t end = begin + 1;
        while (end < clusters.size() && dirs[end] == dirs[begin]) {
            ++end;
        }
        runs.push_back({begin, end, dirs[begin]});
        begin = end;
    }

    auto append_run = [&](const Run &run) {
        if (run.dir == Direction::Rtl) {
            for (size_t i = run.end; i > run.begin; --i) {
                AppendCluster(out, clusters[i - 1]);
            }
        } else {
            for (size_t i = run.begin; i < run.end; ++i) {
                AppendCluster(out, clusters[i]);
            }
        }
    };

    if (base == Direction::Rtl) {
        for (size_t i = runs.size(); i > 0; --i) {
            append_run(runs[i - 1]);
        }
    } else {
        for (const Run &run : runs) {
            append_run(run);
        }
    }
}

} // namespace

const WCHAR *GeneralsX_Prepare_Arabic_RTL(const WCHAR *text)
{
    if (text == nullptr || !ContainsArabic(text)) {
        return text;
    }

    static thread_local std::vector<WCHAR> prepared;
    prepared.clear();

    std::vector<WCHAR> line;

    for (const WCHAR *p = text;; ++p) {
        WCHAR ch = *p;
        if (ch == L'\n' || ch == 0) {
            ShapeAndReorderLine(line, prepared);
            line.clear();

            if (ch == L'\n') {
                prepared.push_back(L'\n');
                continue;
            }
            break;
        }
        line.push_back(ch);
    }

    prepared.push_back(0);
    return prepared.data();
}

#else

const WCHAR *GeneralsX_Prepare_Arabic_RTL(const WCHAR *text)
{
    return text;
}

#endif
