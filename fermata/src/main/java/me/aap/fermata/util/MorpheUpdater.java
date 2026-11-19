package me.aap.fermata.util;

import static android.os.Build.VERSION.SDK_INT;
import static me.aap.fermata.BuildConfig.AUTO;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.R;
import me.aap.utils.app.App;
import me.aap.utils.function.BooleanSupplier;
import me.aap.utils.function.LongSupplier;
import me.aap.utils.function.Supplier;
import me.aap.utils.log.Log;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.pref.PreferenceStore.Pref;

/**
 * Keeps Morphe YouTube up to date. The patching is done by the "Morphe YouTube" GitHub workflow
 * (.github/workflows/morphe-youtube.yml), which patches the latest YouTube version supported by
 * the latest Morphe patches whenever a new Morphe release is out, and publishes the signed APK as a
 * release tagged morphe-youtube-[YouTube version]-[patches version]. This class finds the newest
 * of those releases and installs it, once it's newer than the installed app.
 * <p>
 * Installing requires REQUEST_INSTALL_PACKAGES, which only the Auto flavor declares. Once Fermata
 * has installed Morphe YouTube, Android 12+ updates it without asking.
 */
public final class MorpheUpdater {
	public static final String DEFAULT_REPO = "BigJoys/Fermata-x-Morphe-Youtube";
	public static final String TAG_PREFIX = "morphe-youtube-";
	public static final Pref<BooleanSupplier> AUTO_UPDATE = Pref.b("MORPHE_YT_AUTO_UPDATE", true);
	public static final Pref<Supplier<String>> UPDATE_REPO =
			Pref.s("MORPHE_YT_UPDATE_REPO", DEFAULT_REPO);
	static final Pref<Supplier<String>> INSTALLED_TAG =
			Pref.s("MORPHE_YT_INSTALLED_TAG", (String) null);
	// A release that failed to install, e.g. signed with another key: not retried automatically
	static final Pref<Supplier<String>> FAILED_TAG = Pref.s("MORPHE_YT_FAILED_TAG", (String) null);
	private static final Pref<LongSupplier> LAST_CHECK = Pref.l("MORPHE_YT_LAST_CHECK", 0L);
	private static final long CHECK_INTERVAL = 6 * 60 * 60 * 1000L;
	static final String EXTRA_TAG = "me.aap.fermata.morphe.TAG";
	static final String EXTRA_FILE = "me.aap.fermata.morphe.FILE";
	private static volatile boolean running;

	private MorpheUpdater() {}

	public static boolean isSupported() {
		return AUTO;
	}

	/**
	 * Checks for an update, at most every few hours and only on an unmetered network, e.g. Wi-Fi.
	 * Only updates an installed Morphe YouTube, it's never installed automatically.
	 */
	public static void checkOnStart(@NonNull Context ctx) {
		if (!isSupported() || running) return;
		PreferenceStore ps = MorpheYoutube.getPreferenceStore();
		if (!ps.getBooleanPref(AUTO_UPDATE) || !MorpheYoutube.isEnabled()) return;
		if (!MorpheYoutube.isInstalled(ctx)) return;
		long now = System.currentTimeMillis();
		if (Math.abs(now - ps.getLongPref(LAST_CHECK)) < CHECK_INTERVAL) return;
		ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
		if ((cm == null) || (cm.getActiveNetwork() == null) || cm.isActiveNetworkMetered()) return;
		ps.applyLongPref(LAST_CHECK, now);
		check(ctx, false);
	}

	/**
	 * @param manual started by the user: report the result, install even if not installed yet or
	 *               if this release failed to install before.
	 */
	public static void check(@NonNull Context context, boolean manual) {
		if (!isSupported()) return;
		Context ctx = context.getApplicationContext();
		if (running) {
			if (manual) toast(ctx, ctx.getString(R.string.morphe_update_running));
			return;
		}

		running = true;
		String repo = getRepo();
		if (manual) toast(ctx, ctx.getString(R.string.morphe_update_checking));

		App.get().execute(() -> findLatest(repo)).main().onCompletion((rel, err) -> {
			if (err != null) {
				running = false;
				Log.e(err, "Failed to check for a Morphe YouTube update in ", repo);
				if (manual) toast(ctx, ctx.getString(R.string.morphe_update_failed, err.getMessage()));
				return;
			}
			if (rel == null) {
				running = false;
				if (manual) toast(ctx, ctx.getString(R.string.morphe_update_no_release, repo));
				return;
			}
			if (!needsUpdate(ctx, rel, manual)) {
				running = false;
				if (manual) toast(ctx, ctx.getString(R.string.morphe_update_up_to_date, rel.tag));
				return;
			}
			if (!ctx.getPackageManager().canRequestPackageInstalls()) {
				running = false;
				requestInstallPermission(ctx);
				return;
			}

			Log.i("Installing Morphe YouTube ", rel.tag);
			toast(ctx, ctx.getString(R.string.morphe_update_downloading, rel.tag));
			App.get().execute(() -> {
				File f = download(ctx, rel);
				install(ctx, rel, f);
				return f;
			}).main().onCompletion((f, e) -> {
				running = false;
				if (e == null) return;
				Log.e(e, "Failed to install Morphe YouTube ", rel.tag);
				toast(ctx, ctx.getString(R.string.morphe_update_failed, e.getMessage()));
			});
		});
	}

	static String getRepo() {
		String repo = MorpheYoutube.getPreferenceStore().getStringPref(UPDATE_REPO);
		if (repo != null) {
			repo = repo.trim().replaceFirst("^https?://github\\.com/", "").replaceAll("/+$", "");
		}
		return ((repo == null) || repo.isEmpty()) ? DEFAULT_REPO : repo;
	}

	private static boolean needsUpdate(Context ctx, Release rel, boolean manual) {
		PreferenceStore ps = MorpheYoutube.getPreferenceStore();
		if (!manual && rel.tag.equals(ps.getStringPref(FAILED_TAG))) return false;
		PackageInfo pi = MorpheYoutube.getPackageInfo(ctx);
		if (pi == null) return manual;
		if (rel.tag.equals(ps.getStringPref(INSTALLED_TAG))) return false;
		// Patched after the installed app was installed or updated, by Fermata or by Morphe Manager
		return rel.publishedAt > pi.lastUpdateTime;
	}

	@Nullable
	private static Release findLatest(String repo) throws Exception {
		URL url = new URL("https://api.github.com/repos/" + repo + "/releases?per_page=30");
		HttpURLConnection c = (HttpURLConnection) url.openConnection();
		c.setConnectTimeout(30000);
		c.setReadTimeout(30000);
		c.setRequestProperty("Accept", "application/vnd.github+json");
		c.setRequestProperty("User-Agent", "Fermata");
		String json;

		try {
			int code = c.getResponseCode();
			if (code != 200) throw new IOException("HTTP " + code + ": " + c.getResponseMessage());
			try (InputStream in = c.getInputStream()) {
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				byte[] buf = new byte[8192];
				for (int n = in.read(buf); n != -1; n = in.read(buf)) out.write(buf, 0, n);
				json = out.toString(StandardCharsets.UTF_8.name());
			}
		} finally {
			c.disconnect();
		}

		JSONArray releases = new JSONArray(json);
		Release latest = null;

		for (int i = 0; i < releases.length(); i++) {
			JSONObject r = releases.getJSONObject(i);
			if (r.optBoolean("draft") || r.optBoolean("prerelease")) continue;
			String tag = r.optString("tag_name");
			if (!tag.startsWith(TAG_PREFIX)) continue;
			String published = r.optString("published_at", "");
			if (published.isEmpty()) continue;
			long time = Instant.parse(published).toEpochMilli();
			if ((latest != null) && (latest.publishedAt >= time)) continue;
			JSONArray assets = r.optJSONArray("assets");
			if (assets == null) continue;

			for (int j = 0; j < assets.length(); j++) {
				JSONObject a = assets.getJSONObject(j);
				if (!a.optString("name").endsWith(".apk")) continue;
				latest = new Release(tag, a.getString("browser_download_url"), a.optLong("size"), time);
				break;
			}
		}

		return latest;
	}

	private static File download(Context ctx, Release rel) throws IOException {
		File dir = new File(ctx.getCacheDir(), "morphe");
		File[] old = dir.listFiles();
		if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
			f.delete();
		if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Failed to create " + dir);
		File file = new File(dir, rel.tag + ".apk");
		HttpURLConnection c = (HttpURLConnection) new URL(rel.url).openConnection();
		c.setConnectTimeout(30000);
		c.setReadTimeout(60000);
		c.setRequestProperty("User-Agent", "Fermata");

		try {
			int code = c.getResponseCode();
			if (code != 200) throw new IOException("HTTP " + code + ": " + c.getResponseMessage());
			try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(file)) {
				byte[] buf = new byte[65536];
				for (int n = in.read(buf); n != -1; n = in.read(buf)) out.write(buf, 0, n);
			}
		} catch (IOException ex) {
			//noinspection ResultOfMethodCallIgnored
			file.delete();
			throw ex;
		} finally {
			c.disconnect();
		}

		if ((rel.size > 0) && (file.length() != rel.size)) {
			//noinspection ResultOfMethodCallIgnored
			file.delete();
			throw new IOException("Incomplete download: " + file.length() + " of " + rel.size);
		}

		return file;
	}

	private static void install(Context ctx, Release rel, File file) throws IOException {
		PackageInstaller installer = ctx.getPackageManager().getPackageInstaller();
		PackageInstaller.SessionParams params =
				new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
		params.setAppPackageName(MorpheYoutube.MORPHE_PKG);
		params.setSize(file.length());
		params.setInstallReason(PackageManager.INSTALL_REASON_USER);
		if (SDK_INT >= 31) {
			// No confirmation for updates of the app installed by Fermata
			params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
		}

		int id = installer.createSession(params);
		PackageInstaller.Session session = installer.openSession(id);

		try {
			try (InputStream in = new FileInputStream(file);
					 OutputStream out = session.openWrite("base.apk", 0, file.length())) {
				byte[] buf = new byte[65536];
				for (int n = in.read(buf); n != -1; n = in.read(buf)) out.write(buf, 0, n);
				session.fsync(out);
			}

			Intent intent = new Intent(ctx, MorpheInstallReceiver.class);
			intent.putExtra(EXTRA_TAG, rel.tag);
			intent.putExtra(EXTRA_FILE, file.getAbsolutePath());
			int flags = PendingIntent.FLAG_UPDATE_CURRENT;
			if (SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
			PendingIntent pi = PendingIntent.getBroadcast(ctx, id, intent, flags);
			session.commit(pi.getIntentSender());
		} catch (IOException | RuntimeException ex) {
			session.abandon();
			throw ex;
		} finally {
			session.close();
		}
	}

	private static void requestInstallPermission(Context ctx) {
		toast(ctx, ctx.getString(R.string.morphe_update_permission));
		Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
				Uri.parse("package:" + ctx.getPackageName()));
		i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		try {
			ctx.startActivity(i);
		} catch (Exception ex) {
			Log.e(ex, "Failed to request the permission to install apps");
		}
	}

	static void toast(Context ctx, String msg) {
		FermataApplication.get().getHandler().post(() ->
				Toast.makeText(ctx.getApplicationContext(), msg, Toast.LENGTH_LONG).show());
	}

	private static final class Release {
		final String tag;
		final String url;
		final long size;
		final long publishedAt;

		Release(String tag, String url, long size, long publishedAt) {
			this.tag = tag;
			this.url = url;
			this.size = size;
			this.publishedAt = publishedAt;
		}
	}
}
