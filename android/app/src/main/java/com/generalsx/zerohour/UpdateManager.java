package com.generalsx.zerohour;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Iterator;

/**
 * GeneralsX @tweak Android port release 20/10/2026 Engine selection only.
 *
 * This class used to be the launcher's whole update system: it fetched a
 * signed manifest from the repository's {@code updates} branch, applied
 * remote settings, delivered the Help page's support card, and downloaded
 * newer engines to run instead of the APK's own. The release build carries
 * none of that -- no update checks, no downloads, no remote settings. The
 * app is exactly what was installed, and a new version means a new APK.
 *
 * What remains is only the local half that {@link GeneralsZHActivity} calls
 * before loading the engine: if a downloaded engine from an earlier build
 * is still on this device (files/update/engine/<seq>/, with its marker
 * files), resolve whether it may run; otherwise report none, and the APK's
 * own engine runs. Nothing here touches the network.
 */
final class UpdateManager {
    private static final String TAG = "GXUpdate";

    static final String[] ENGINE_LIBS = { "libmain.so", "libmain60.so" };

    private static final String PREFS = "gx_update";
    private static final String KEY_DEPS_OK_FOR = "deps_ok_for";

    private UpdateManager() {
    }

    // ---------------------------------------------------------------------------------------
    // Paths

    static File updateDir(Context ctx) {
        return new File(ctx.getFilesDir(), "update");
    }

    private static File engineRoot(Context ctx) {
        return new File(updateDir(ctx), "engine");
    }

    private static File activeEngineMarker(Context ctx) {
        return new File(updateDir(ctx), "engine_active.txt");
    }

    /** Written before an updated engine is loaded, deleted by the engine at its main menu. */
    private static File bootPendingMarker(Context ctx) {
        return new File(updateDir(ctx), "boot_pending");
    }

    private static File badEngineMarker(Context ctx) {
        return new File(updateDir(ctx), "engine_bad.txt");
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------------------------------
    // Engine selection (called by GeneralsZHActivity before SDL loads the libraries)

    /** The commit count the APK's own engine was built at; 0 if the APK does not say. */
    static int bundledEngineSeq(Context ctx) {
        try (InputStream in = ctx.getAssets().open("engine_build.txt")) {
            String text = new String(readAll(in), StandardCharsets.US_ASCII).trim();
            return Integer.parseInt(text);
        } catch (IOException | NumberFormatException e) {
            return 0;
        }
    }

    /** The downloaded engine that is ready to run, or 0 if the APK's own engine should run. */
    static int activeEngineSeq(Context ctx) {
        int seq = readInt(activeEngineMarker(ctx));
        if (seq <= 0 || seq <= bundledEngineSeq(ctx) || seq == readInt(badEngineMarker(ctx))) {
            return 0;
        }
        for (String lib : ENGINE_LIBS) {
            if (!new File(new File(engineRoot(ctx), Integer.toString(seq)), lib).isFile()) {
                return 0;
            }
        }
        if (!dependenciesStillMatch(ctx, seq)) {
            return 0;
        }
        return seq;
    }

    /** Absolute path of an updated engine library, or null to load the APK's own. */
    static String activeEngineLibrary(Context ctx, String libName) {
        int seq = activeEngineSeq(ctx);
        if (seq == 0) {
            return null;
        }
        return new File(new File(engineRoot(ctx), Integer.toString(seq)), libName).getAbsolutePath();
    }

    /**
     * Record that an updated engine is about to start. If the marker is still there from the
     * previous start of the same engine -- it never reached the main menu -- count it; on the
     * second such failure the engine is marked bad and the APK's own runs from then on.
     * @return true if the updated engine may be used for this start.
     */
    static boolean noteEngineBoot(Context ctx, int seq) {
        File pending = bootPendingMarker(ctx);
        int failures = 0;
        if (pending.isFile()) {
            String[] parts = readText(pending).trim().split(" ");
            if (parts.length == 2 && Integer.toString(seq).equals(parts[0])) {
                try {
                    failures = Integer.parseInt(parts[1]) + 1;
                } catch (NumberFormatException ignored) {
                    failures = 1;
                }
            }
        }
        if (failures >= 2) {
            Log.w(TAG, "engine " + seq + " failed to reach the main menu twice; using the APK's engine");
            writeText(badEngineMarker(ctx), Integer.toString(seq));
            pending.delete();
            return false;
        }
        writeText(pending, seq + " " + failures);
        return true;
    }

    // ---------------------------------------------------------------------------------------
    // Library compatibility

    private static boolean librariesMatch(Context ctx, JSONObject requires) throws IOException {
        File libDir = new File(ctx.getApplicationInfo().nativeLibraryDir);
        for (Iterator<String> it = requires.keys(); it.hasNext(); ) {
            String lib = it.next();
            File installed = new File(libDir, lib);
            if (!installed.isFile() || !requires.optString(lib, "").equalsIgnoreCase(sha256(installed))) {
                Log.i(TAG, "engine needs a different " + lib + " than this APK installed");
                return false;
            }
        }
        return true;
    }

    /** Re-checked after the APK itself is updated, since that replaces the libraries. */
    private static boolean dependenciesStillMatch(Context ctx, int seq) {
        String stamp = depsStamp(ctx, seq);
        if (stamp.equals(prefs(ctx).getString(KEY_DEPS_OK_FOR, ""))) {
            return true;
        }
        try {
            File manifestFile = new File(updateDir(ctx), "manifest.json");
            JSONObject engine = new JSONObject(readText(manifestFile)).optJSONObject("engine");
            if (engine == null || engine.optInt("seq", 0) != seq) {
                return false;
            }
            JSONObject requires = engine.optJSONObject("requires_libs");
            if (requires != null && librariesMatch(ctx, requires)) {
                prefs(ctx).edit().putString(KEY_DEPS_OK_FOR, stamp).apply();
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "could not re-check the engine's libraries", e);
        }
        return false;
    }

    private static String depsStamp(Context ctx, int seq) {
        long installed = 0L;
        try {
            PackageInfo info = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            installed = info.lastUpdateTime;
        } catch (Exception ignored) {
            // an unknown install time just forces a re-check
        }
        return seq + ":" + installed;
    }

    static String sha256(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                digest.update(buf, 0, n);
            }
            return hex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------------------------------
    // Small file helpers

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static String readText(File file) {
        try (InputStream in = new FileInputStream(file)) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static int readInt(File file) {
        try {
            return file.isFile() ? Integer.parseInt(readText(file).trim()) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void writeText(File file, String text) {
        try {
            writeBytes(file, text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.w(TAG, "cannot write " + file, e);
        }
    }

    private static void writeBytes(File file, byte[] bytes) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("cannot create " + parent);
        }
        File tmp = new File(file.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes);
        }
        if (!tmp.renameTo(file)) {
            file.delete();
            if (!tmp.renameTo(file)) {
                throw new IOException("cannot write " + file);
            }
        }
    }
}
