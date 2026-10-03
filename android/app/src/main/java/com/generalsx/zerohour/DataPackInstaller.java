/*
**	Command & Conquer Generals Zero Hour(tm)
**	Copyright 2025 Electronic Arts Inc.
**
**	This program is free software: you can redistribute it and/or modify
**	it under the terms of the GNU General Public License as published by
**	the Free Software Foundation, either version 3 of the License, or
**	(at your option) any later version.
**
**	This program is distributed in the hope that it will be useful,
**	but WITHOUT ANY WARRANTY; without even the implied warranty of
**	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
**	GNU General Public License for more details.
**
**	You should have received a copy of the GNU General Public License
**	along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

// GeneralsX @feature Android port 13/09/2026
//
// Fetches the GeneralsOnline game data this device needs to play against PC
// players, so that playing online needs no PC at all.
//
// Two things live outside the retail game and are not optional online. The
// community data patch is an INI archive the PC client mounts on every launch
// (ArchiveFileSystem::loadMods), and it moves the INI checksum every lobby is
// compared against: retail data alone computes 4272612339, retail plus the
// patch computes 2180732466, which is what every PC-hosted lobby reports. The
// community maps are the maps those lobbies are actually played on. Without
// either, a join is refused before it starts.
//
// Neither ships with the game and neither is served by any API the client
// talks to -- on Windows they arrive inside the installer. They are also,
// however, published as a plain ZIP next to a small JSON manifest, and that is
// what this reads: manifest for the version, size and SHA-256, then the ZIP,
// then the two directories out of it that mean anything on Android. The
// Windows binaries in the same archive are ignored.
//
// Only those two prefixes are extracted, and every entry is checked against
// its destination before anything is written -- a ZIP is an untrusted list of
// paths, and "GeneralsOnlineGameData/../../.." would otherwise be a write
// wherever it pointed.

package com.generalsx.zerohour;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class DataPackInstaller {

    private DataPackInstaller() {}

    private static final String MANIFEST_URL = "https://cdn.playgenerals.online/manifest.json";
    // GeneralsX @feature Android port 03/10/2026
    // The community patch is published independently by TheSuperHackers. The
    // release asset is allowed to carry the Android-normalized suffix/hash in
    // its filename; the engine still receives one canonical filename.
    private static final String COMMUNITY_PATCH_RELEASE_API =
        "https://api.github.com/repos/TheSuperHackers/GeneralsGamePatch2/releases/latest";
    private static final String COMMUNITY_PATCH_ASSET_PREFIX =
        "500_900_CommunityPatch_CoreINI";
    private static final String COMMUNITY_PATCH_CANONICAL_NAME =
        "500_900_CommunityPatch_CoreINI.big";
    private static final String COMMUNITY_PATCH_INSTALLED_RELATIVE =
        "GeneralsOnlineGameData/" + COMMUNITY_PATCH_CANONICAL_NAME;

    private static final class CommunityPatchRelease {
        final String version;
        final String assetName;
        final String downloadUrl;
        final long size;
        final String sha256;

        CommunityPatchRelease(String version, String assetName, String downloadUrl,
                              long size, String sha256) {
            this.version = version;
            this.assetName = assetName;
            this.downloadUrl = downloadUrl;
            this.size = size;
            this.sha256 = sha256;
        }

        boolean isZip() {
            return assetName.toLowerCase(Locale.US).endsWith(".zip");
        }
    }

    /**
     * GeneralsX @feature Android port 27/09/2026 The package's own manifest address can be
     * changed from the signed update settings (datapack_manifest_url), so a move of the
     * GeneralsOnline CDN does not need a new APK. Only an https address is taken.
     */
    static String manifestUrl(Context ctx) {
        String url = UpdateManager.remoteConfig(ctx, "datapack_manifest_url", MANIFEST_URL);
        return url.startsWith("https://") ? url : MANIFEST_URL;
    }

    /** The version the GeneralsOnline CDN offers now, or null if it cannot be reached. */
    static String latestVersion(Context ctx) {
        try {
            CommunityPatchRelease release = fetchCommunityPatchRelease();
            return release != null && !release.version.isEmpty() ? release.version : null;
        } catch (Exception e) {
            NetworkTrace.write(ctx, "[datapack] latest community patch check failed: " + e);
            return null;
        }
    }

    /** Resolves the latest CommunityPatch Core INI asset from the public community repository. */
    private static CommunityPatchRelease fetchCommunityPatchRelease() throws Exception {
        JSONObject release = new JSONObject(fetchText(COMMUNITY_PATCH_RELEASE_API, true));
        String version = release.optString("tag_name", "").trim();
        if (version.isEmpty()) {
            version = release.optString("name", "").trim();
        }

        JSONArray assets = release.optJSONArray("assets");
        if (assets == null || assets.length() == 0) {
            throw new IOException("community release has no assets");
        }

        CommunityPatchRelease best = null;
        int bestRank = Integer.MAX_VALUE;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) {
                continue;
            }
            String name = asset.optString("name", "").trim();
            String lower = name.toLowerCase(Locale.US);
            String prefix = COMMUNITY_PATCH_ASSET_PREFIX.toLowerCase(Locale.US);
            if (!lower.startsWith(prefix) || !(lower.endsWith(".zip") || lower.endsWith(".big"))) {
                continue;
            }

            String url = asset.optString("browser_download_url", "").trim();
            if (!url.startsWith("https://")) {
                continue;
            }

            // Prefer the release ZIP (the official ModBuilder asset), but accept
            // a directly published .BIG or a suffixed/hash-named variant too.
            int rank = lower.endsWith(".zip") ? 0 : 10;
            if (!lower.equals((COMMUNITY_PATCH_ASSET_PREFIX + ".zip").toLowerCase(Locale.US))
                    && lower.equals((COMMUNITY_PATCH_ASSET_PREFIX + ".big").toLowerCase(Locale.US))) {
                rank += 1;
            }

            if (best == null || rank < bestRank) {
                String digest = asset.optString("digest", "").trim();
                if (digest.regionMatches(true, 0, "sha256:", 0, 7)) {
                    digest = digest.substring(7);
                }
                if (digest.isEmpty()) {
                    digest = asset.optString("sha256", "").trim();
                }
                best = new CommunityPatchRelease(
                    version.isEmpty() ? "unknown" : version,
                    name,
                    url,
                    asset.optLong("size", -1),
                    digest
                );
                bestRank = rank;
            }
        }

        if (best == null) {
            throw new IOException("community release has no Core INI asset");
        }
        return best;
    }

    /** The only two directories in the package that mean anything here. */
    private static final String[] WANTED_PREFIXES = {
        "GeneralsOnlineGameData/",
        "Maps/",
    };

    private static final String PREFS_NAME = "generals_online";
    private static final String PREF_INSTALLED_VERSION = "datapack_version";
    private static final String PREF_MANUAL_INSTALLED = "datapack_manual_installed";
    private static final String MANUAL_VERSION = "manual";

    /**
     * Presence turns the patch off without removing it, and the engine is the
     * only reader (ArchiveFileSystem::loadMods). It sits in the user-data
     * folder beside the patch rather than in the game folder, so the switch
     * works whether or not a game folder has been picked yet.
     */
    private static final String DISABLE_MARKER = "gx_no_community_patch.txt";

    /**
     * What the last install actually wrote, so uninstalling removes that and
     * nothing else. Deleting Maps/ wholesale would take the player's own maps
     * with it -- they share the directory.
     */
    private static final String INSTALLED_LIST = "datapack-installed.json";

    /** Where the engine looks: BuildUserDataPathFromRegistry's Android branch. */
    static File userDataDir() {
        return new File(android.os.Environment.getExternalStorageDirectory(),
            "Generals/Command and Conquer Generals Zero Hour Data");
    }

    static File communityPatchFile() {
        File dir = new File(userDataDir(), "GeneralsOnlineGameData");
        File canonical = new File(dir, COMMUNITY_PATCH_CANONICAL_NAME);
        normalizeCommunityPatchName(dir, canonical);
        return canonical;
    }

    /**
     * Community releases sometimes append the INI CRC/hash to the BIG filename
     * (for example ..._81FB5632.big). The engine intentionally does not: it
     * opens one canonical path. Normalize any existing compatible file in place.
     */
    private static void normalizeCommunityPatchName(File dir, File canonical) {
        if (canonical.isFile() || !dir.isDirectory()) {
            return;
        }

        File[] candidates = dir.listFiles((file, name) ->
            name.matches("(?i)^500_900_CommunityPatch_CoreINI(?:_[0-9a-f]+)?\\.big$"));
        if (candidates == null || candidates.length == 0) {
            return;
        }

        // Never bake an old CRC suffix into the selector. If several
        // compatible names were copied manually, prefer the newest file.
        java.util.Arrays.sort(candidates, (a, b) ->
            Long.compare(b.lastModified(), a.lastModified()));

        File candidate = candidates[0];
        if (candidate.renameTo(canonical)) {
            return;
        }

        // renameTo() can fail across Android storage providers; copy as a safe fallback.
        try (InputStream in = new BufferedInputStream(new FileInputStream(candidate));
             OutputStream out = new FileOutputStream(canonical)) {
            copy(in, out);
            if (!candidate.delete()) {
                // Keeping the original is harmless; the engine uses the canonical copy.
            }
        } catch (IOException ignored) {
            canonical.delete();
        }
    }

    private static void removeSuffixedCommunityPatches(File dir, File canonical) {
        if (dir == null || !dir.isDirectory()) {
            return;
        }
        File[] stale = dir.listFiles((file, name) ->
            name.matches("(?i)^500_900_CommunityPatch_CoreINI(?:_[0-9a-f]+)\\.big$")
                && !file.equals(canonical));
        if (stale != null) {
            for (File file : stale) {
                file.delete();
            }
        }
    }

    static String installedVersion(Context ctx) {
        File patch = communityPatchFile();
        android.content.SharedPreferences prefs =
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (!patch.isFile()) {
            prefs.edit().remove(PREF_INSTALLED_VERSION).remove(PREF_MANUAL_INSTALLED).apply();
            return null;
        }

        if (prefs.getBoolean(PREF_MANUAL_INSTALLED, false)) {
            return MANUAL_VERSION;
        }

        String version = prefs.getString(PREF_INSTALLED_VERSION, null);
        if (version == null || version.isEmpty()) {
            // The patch is real and readable, but this launcher has no install record.
            // Treat it as a manual install everywhere, not only on the multiplayer
            // screen, so no update path can mistake it for missing data.
            prefs.edit().putBoolean(PREF_MANUAL_INSTALLED, true).apply();
            NetworkTrace.write(ctx,
                "[datapack] automatically adopted existing manual community data from " +
                patch.getAbsolutePath());
            return MANUAL_VERSION;
        }

        return version;
    }

    /** Adopt a community patch copied into user data outside this launcher. */
    static boolean adoptManualInstall(Context ctx) {
        if (!communityPatchFile().isFile()) {
            return false;
        }
        android.content.SharedPreferences prefs =
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (prefs.getString(PREF_INSTALLED_VERSION, null) != null) {
            return false;
        }
        if (prefs.getBoolean(PREF_MANUAL_INSTALLED, false)) {
            return true;
        }
        prefs.edit()
            .remove(PREF_INSTALLED_VERSION)
            .putBoolean(PREF_MANUAL_INSTALLED, true)
            .apply();
        NetworkTrace.write(ctx,
            "[datapack] adopted manually installed community data from " +
            communityPatchFile().getAbsolutePath());
        return true;
    }

    static boolean isManualInstall(Context ctx) {
        return communityPatchFile().isFile()
            && ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_MANUAL_INSTALLED, false);
    }

    static boolean isEnabled() {
        return !new File(userDataDir(), DISABLE_MARKER).isFile();
    }

    /** Returns true if the state now matches what was asked for. */
    static boolean setEnabled(Context ctx, boolean enabled) {
        File marker = new File(userDataDir(), DISABLE_MARKER);
        if (enabled) {
            boolean ok = !marker.exists() || marker.delete();
            NetworkTrace.write(ctx, "[datapack] community patch enabled=" + ok);
            return ok;
        }
        try {
            File parent = marker.getParentFile();
            if (parent != null && !parent.isDirectory()) {
                parent.mkdirs();
            }
            try (FileWriter out = new FileWriter(marker)) {
                out.write("The community data patch is disabled while this file exists.\n");
            }
            NetworkTrace.write(ctx, "[datapack] community patch disabled");
            return true;
        } catch (IOException e) {
            NetworkTrace.write(ctx, "[datapack] could not disable: " + e);
            return false;
        }
    }

    /**
     * Removes exactly the files the last install wrote, then any directories
     * left empty by that. Returns how many files went, or -1 if there was no
     * record to work from.
     */
    static int uninstall(Context ctx) {
        List<String> installed = readInstalledList(ctx);
        if (installed == null) {
            return -1;
        }

        File root = userDataDir();
        int removed = 0;
        for (String relative : installed) {
            File victim = new File(root, relative);
            if (victim.isFile() && victim.delete()) {
                removed++;
            }
        }

        // Second pass: the directories those files were the only contents of.
        // Deepest first, so a directory whose children just went can still go.
        List<String> dirs = new ArrayList<>();
        for (String relative : installed) {
            int cut = relative.lastIndexOf('/');
            while (cut > 0) {
                String dir = relative.substring(0, cut);
                if (!dirs.contains(dir)) {
                    dirs.add(dir);
                }
                cut = dir.lastIndexOf('/');
            }
        }
        java.util.Collections.sort(dirs);
        java.util.Collections.reverse(dirs);
        for (String dir : dirs) {
            File candidate = new File(root, dir);
            String[] left = candidate.list();
            if (left != null && left.length == 0) {
                candidate.delete();
            }
        }

        new File(ctx.getFilesDir(), INSTALLED_LIST).delete();
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(PREF_INSTALLED_VERSION).apply();

        NetworkTrace.write(ctx, "[datapack] uninstalled " + removed + " file(s)");
        return removed;
    }

    private static List<String> readInstalledList(Context ctx) {
        File list = new File(ctx.getFilesDir(), INSTALLED_LIST);
        if (!list.isFile()) {
            return null;
        }
        try {
            java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
            try (InputStream in = new FileInputStream(list)) {
                copy(in, raw);
            }
            JSONArray array = new JSONArray(raw.toString("UTF-8"));
            List<String> paths = new ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++) {
                paths.add(array.getString(i));
            }
            return paths;
        } catch (Exception e) {
            NetworkTrace.write(ctx, "[datapack] could not read the installed list: " + e);
            return null;
        }
    }

    private static void writeInstalledList(Context ctx, List<String> paths) {
        try (FileWriter out = new FileWriter(new File(ctx.getFilesDir(), INSTALLED_LIST))) {
            out.write(new JSONArray(paths).toString());
        } catch (Exception e) {
            // Losing this costs the player a clean uninstall, not the install
            // itself -- so it is worth a log line and nothing more.
            NetworkTrace.write(ctx, "[datapack] could not record the installed list: " + e);
        }
    }

    /** Phases the caller turns into something on screen. */
    interface Progress {
        void onChecking();
        void onDownloading(long bytes, long total);
        void onInstalling();
    }

    static final class Result {
        final boolean ok;
        final String version;
        final int filesWritten;
        final String error;

        private Result(boolean ok, String version, int filesWritten, String error) {
            this.ok = ok;
            this.version = version;
            this.filesWritten = filesWritten;
            this.error = error;
        }

        static Result success(String version, int filesWritten) {
            return new Result(true, version, filesWritten, null);
        }

        static Result failure(String error) {
            return new Result(false, null, 0, error);
        }
    }

    /**
     * Downloads and installs the current package. Blocking -- call it off the
     * main thread. Never throws: every failure comes back as Result.failure so
     * the caller has one thing to render.
     */
    /** Held for a whole install: two at once would extract over each other. */
    static final Object INSTALL_LOCK = new Object();

    static Result install(Context ctx, Progress progress) {
        synchronized (INSTALL_LOCK) {
            return installCommunityPatchLocked(ctx, progress);
        }
    }

    /**
     * Downloads only the current Core INI package from the community repository,
     * verifies the repository asset, and installs the BIG under the exact filename
     * the Android engine consumes. This is deliberately independent of the Windows
     * portable bundle so a PC archive rename cannot break Android.
     */
    private static Result installCommunityPatchLocked(Context ctx, Progress progress) {
        File tempDownload = null;
        File tempBig = null;
        try {
            progress.onChecking();
            CommunityPatchRelease release = fetchCommunityPatchRelease();

            tempDownload = new File(ctx.getCacheDir(), "community-core-ini.download");
            tempBig = new File(ctx.getCacheDir(), "community-core-ini.big.tmp");

            String actualSha = download(release.downloadUrl, tempDownload, release.size, progress);
            if (!release.sha256.isEmpty() && !release.sha256.equalsIgnoreCase(actualSha)) {
                NetworkTrace.write(ctx,
                    "[datapack] community asset checksum mismatch: expected "
                        + release.sha256 + " got " + actualSha);
                return Result.failure("community asset checksum mismatch");
            }

            progress.onInstalling();
            tempBig.delete();

            if (release.isZip()) {
                extractCommunityPatchFromZip(tempDownload, tempBig);
            } else {
                try (InputStream in = new BufferedInputStream(new FileInputStream(tempDownload));
                     OutputStream out = new FileOutputStream(tempBig)) {
                    copy(in, out);
                }
            }

            if (!tempBig.isFile() || tempBig.length() <= 0) {
                return Result.failure("community package did not contain a Core INI BIG");
            }

            File target = communityPatchFile();
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                return Result.failure("could not create " + parent.getAbsolutePath());
            }

            // Replace atomically where the storage provider supports it; otherwise
            // copy the verified temporary BIG to the fixed destination.
            File stagedTarget = new File(parent, COMMUNITY_PATCH_CANONICAL_NAME + ".part");
            stagedTarget.delete();
            try (InputStream in = new BufferedInputStream(new FileInputStream(tempBig));
                 OutputStream out = new FileOutputStream(stagedTarget)) {
                copy(in, out);
            }
            if (target.isFile() && !target.delete()) {
                stagedTarget.delete();
                return Result.failure("could not replace existing community patch");
            }
            if (!stagedTarget.renameTo(target)) {
                try (InputStream in = new BufferedInputStream(new FileInputStream(stagedTarget));
                     OutputStream out = new FileOutputStream(target)) {
                    copy(in, out);
                }
                stagedTarget.delete();
            }

            if (!target.isFile() || target.length() != tempBig.length()) {
                return Result.failure("installed community patch failed verification");
            }

            // Remove stale suffixed copies after the canonical file is verified.
            // The engine reads only the canonical filename, so keeping old
            // copies wastes storage and can confuse manual file checks.
            removeSuffixedCommunityPatches(target.getParentFile(), target);

            List<String> written = new ArrayList<>();
            written.add(COMMUNITY_PATCH_INSTALLED_RELATIVE);
            writeInstalledList(ctx, written);

            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(PREF_MANUAL_INSTALLED)
                .putString(PREF_INSTALLED_VERSION, release.version)
                .apply();
            UpdateManager.noteDatapackLatest(ctx, release.version);

            NetworkTrace.write(ctx,
                "[datapack] installed " + release.assetName + " (" + release.version + ") as "
                    + target.getAbsolutePath());

            return Result.success(release.version, 1);
        } catch (Exception e) {
            String message = e.getMessage() != null ? e.getMessage() : e.toString();
            NetworkTrace.write(ctx, "[datapack] failed: " + message);
            return Result.failure(message);
        } finally {
            if (tempDownload != null) {
                tempDownload.delete();
            }
            if (tempBig != null) {
                tempBig.delete();
            }
            File part = new File(new File(userDataDir(), "GeneralsOnlineGameData"),
                COMMUNITY_PATCH_CANONICAL_NAME + ".part");
            part.delete();
        }
    }

    /**
     * Extracts any Core INI BIG whose basename matches the community naming scheme.
     * The ZIP path itself is never used as an output path, preventing ZIP-slip.
     */
    private static void extractCommunityPatchFromZip(File zipFile, File destination)
            throws IOException {
        boolean found = false;
        try (ZipInputStream zip = new ZipInputStream(
                new BufferedInputStream(new FileInputStream(zipFile)))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                if (entry.isDirectory()) {
                    continue;
                }
                int slash = name.lastIndexOf('/');
                String basename = slash >= 0 ? name.substring(slash + 1) : name;
                if (!basename.matches("(?i)^500_900_CommunityPatch_CoreINI(?:_[0-9a-f]+)?\\.big$")) {
                    continue;
                }

                try (OutputStream out = new FileOutputStream(destination)) {
                    copy(zip, out);
                }
                found = true;
                break;
            }
        }

        if (!found) {
            throw new IOException("community ZIP contains no Core INI BIG");
        }
    }

    private static String fetchText(String url, boolean fresh) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setUseCaches(!fresh);
            conn.setRequestProperty("Accept", url.startsWith("https://api.github.com/")
                ? "application/vnd.github+json"
                : "application/json");
            conn.setRequestProperty("User-Agent", "GeneralsXZH-Android");
            if (url.startsWith("https://api.github.com/")) {
                conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            }
            conn.setRequestProperty("Cache-Control", fresh ? "no-cache, no-store" : "no-cache");
            conn.setRequestProperty("Pragma", "no-cache");
            conn.setRequestProperty("Accept-Encoding", "identity");

            int status = conn.getResponseCode();
            if (status != 200) {
                throw new IOException("HTTP " + status + " from manifest");
            }

            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            copy(conn.getInputStream(), out);
            return out.toString("UTF-8");
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** Fetch the manifest with a cache-busting query after an integrity mismatch. */
    private static String fetchTextFresh(String url) throws IOException {
        String separator = url.contains("?") ? "&" : "?";
        return fetchText(url + separator + "_gx_refresh=" + System.currentTimeMillis(), true);
    }

    /** Streams the package to disk and returns its SHA-256, lowercase hex. */
    private static String download(String url, File dest, long expectedSize, Progress progress)
            throws IOException, java.security.NoSuchAlgorithmException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setUseCaches(false);
            conn.setRequestProperty("Cache-Control", "no-cache, no-store");
            conn.setRequestProperty("Pragma", "no-cache");
            conn.setRequestProperty("Accept-Encoding", "identity");

            int status = conn.getResponseCode();
            if (status != 200) {
                throw new IOException("HTTP " + status + " downloading the package");
            }

            long total = conn.getContentLengthLong();
            if (total <= 0) {
                total = expectedSize;
            }

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new BufferedInputStream(conn.getInputStream());
                 OutputStream out = new FileOutputStream(dest)) {
                byte[] buffer = new byte[64 * 1024];
                long done = 0;
                long lastReported = -1;
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    done += read;
                    // One update per percent, not per buffer: 30MB in 64KB
                    // chunks is ~500 posts to the main thread otherwise.
                    long percent = total > 0 ? (done * 100 / total) : -1;
                    if (percent != lastReported) {
                        lastReported = percent;
                        progress.onDownloading(done, total);
                    }
                }
            }

            if (expectedSize > 0 && dest.length() != expectedSize) {
                throw new IOException("download size mismatch: expected "
                    + expectedSize + " bytes, got " + dest.length());
            }

            return toHex(digest.digest());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * GeneralsX @feature Android port 27/09/2026 The PC client's EXE checksum, from the PC
     * executable in this very package. PC lobbies refuse a client whose checksum differs from
     * the host's, and "Play with PC players" claims the PC number -- which every PC release
     * changes. The package is the PC release, so the executable is right here: this runs the
     * first half of GlobalData::generateExeCRC() (rotate left by one, add the byte, over the
     * executable, then the 1.4 version number) and leaves the state in files/update, where the
     * engine adds the two .scb scripts from its own file system and claims the result
     * (GlobalData.cpp). scripts/update/pc-exe-crc.py is the same computation on a PC.
     */
    static final String PC_EXE_NAME = "GeneralsOnlineZH_60.exe";
    static final String PC_EXE_SEED_FILE = "pc_exe_crc_seed.txt";
    // generateExeCRC() stops after 1001 blocks of 64 KiB; the same limit keeps the two equal.
    private static final long PC_EXE_CRC_LIMIT = 1001L * 65536L;
    private static final int PC_VERSION_NUMBER = (1 << 16) | 4;

    private static long crcFeed(long crc, int b) {
        long rotated = ((crc << 1) | (crc >>> 31)) & 0xFFFFFFFFL;
        return (rotated + (b & 0xFF)) & 0xFFFFFFFFL;
    }

    private static long pcExeCrcState(InputStream exe) throws IOException {
        long crc = 0;
        long done = 0;
        byte[] buffer = new byte[64 * 1024];
        int read;
        while (done < PC_EXE_CRC_LIMIT && (read = exe.read(buffer)) > 0) {
            int use = (int) Math.min(read, PC_EXE_CRC_LIMIT - done);
            for (int i = 0; i < use; i++) {
                crc = crcFeed(crc, buffer[i]);
            }
            done += use;
        }
        for (int i = 0; i < 4; i++) {
            crc = crcFeed(crc, PC_VERSION_NUMBER >>> (8 * i));
        }
        return crc;
    }

    private static void writePcExeCrcSeed(Context ctx, long seed, String version) throws IOException {
        File dir = new File(ctx.getFilesDir(), "update");
        File file = new File(dir, PC_EXE_SEED_FILE);
        if (seed < 0) {
            // A package without the PC executable: the settings' number is the better guess.
            file.delete();
            return;
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("could not create " + dir.getAbsolutePath());
        }
        try (FileWriter out = new FileWriter(file)) {
            out.write(seed + "\n" + version + "\n");
        }
        NetworkTrace.write(ctx, "[datapack] PC exe checksum state " + seed + " from " + PC_EXE_NAME);
    }

    /** Whether the installed package's PC checksum has been computed (see PC_EXE_NAME). */
    static boolean hasPcExeCrcSeed(Context ctx) {
        return new File(new File(ctx.getFilesDir(), "update"), PC_EXE_SEED_FILE).isFile();
    }

    /** Extracts the wanted prefixes into targetRoot, listing what it wrote. */
    private static List<String> extract(File zipFile, File targetRoot, long[] pcExeCrc)
            throws IOException {
        String rootPath = targetRoot.getCanonicalPath() + File.separator;
        List<String> written = new ArrayList<>();

        try (ZipInputStream zip = new ZipInputStream(
                new BufferedInputStream(new java.io.FileInputStream(zipFile)))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                if (name.equalsIgnoreCase(PC_EXE_NAME)) {
                    pcExeCrc[0] = pcExeCrcState(zip);
                    continue;
                }
                if (!isWanted(name)) {
                    continue;
                }

                File out = new File(targetRoot, name);
                // Zip slip: the entry name decides the path, and the entry
                // name comes from a file off the network.
                if (!out.getCanonicalPath().startsWith(rootPath)) {
                    throw new IOException("package entry escapes its directory: " + name);
                }

                if (entry.isDirectory()) {
                    out.mkdirs();
                    continue;
                }

                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("could not create " + parent.getAbsolutePath());
                }

                try (OutputStream dest = new FileOutputStream(out)) {
                    copy(zip, dest);
                }
                written.add(name);
            }
        }

        if (written.isEmpty()) {
            throw new IOException("package contained none of the expected data");
        }
        return written;
    }

    private static boolean isWanted(String name) {
        for (String prefix : WANTED_PREFIXES) {
            if (name.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return true;
            }
        }
        return false;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(String.format(Locale.US, "%02x", b));
        }
        return hex.toString();
    }
}
