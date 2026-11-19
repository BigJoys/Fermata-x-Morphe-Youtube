package me.aap.fermata.util;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static me.aap.fermata.BuildConfig.AUTO;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.utils.function.BooleanSupplier;
import me.aap.utils.log.Log;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.pref.PreferenceStore.Pref;

/**
 * Morphe YouTube (https://morphe.software) replaces YouTube: the YouTube entry opens the Morphe
 * YouTube app when it's installed. On a car screen it's only opened in mirroring mode: without it
 * an app started from here would show up on the phone instead, so the built-in web player is used.
 */
public final class MorpheYoutube {
	public static final String MORPHE_PKG = "app.morphe.android.youtube";
	public static final String YOUTUBE_PKG = "com.google.android.youtube";
	// Same as LauncherActivity.INTENT_EXTRA_MODE
	private static final String INTENT_EXTRA_MIRROR_MODE = "fermata.mirror.mode";
	public static final Pref<BooleanSupplier> USE_MORPHE = Pref.b("MORPHE_YT_USE", true);

	private MorpheYoutube() {}

	public static PreferenceStore getPreferenceStore() {
		return FermataApplication.get().getPreferenceStore();
	}

	public static boolean isEnabled() {
		return getPreferenceStore().getBooleanPref(USE_MORPHE);
	}

	public static boolean isInstalled(@NonNull Context ctx) {
		return getPackageInfo(ctx) != null;
	}

	@Nullable
	public static PackageInfo getPackageInfo(@NonNull Context ctx) {
		try {
			return ctx.getPackageManager().getPackageInfo(MORPHE_PKG, 0);
		} catch (PackageManager.NameNotFoundException ex) {
			return null;
		}
	}

	/**
	 * The package to use for YouTube: Morphe YouTube if installed, otherwise the stock YouTube app.
	 */
	public static String getYoutubePackage(@NonNull Context ctx) {
		return isInstalled(ctx) ? MORPHE_PKG : YOUTUBE_PKG;
	}

	/**
	 * Whether the YouTube entry should open the Morphe YouTube app instead of the built-in player.
	 */
	public static boolean shouldOpenApp(@NonNull MainActivityDelegate a) {
		if (!isEnabled() || !isInstalled(a.getContext())) return false;
		// Projected car screen without mirroring: the app would start on the phone, not in the car
		return !AUTO || !a.getAppActivity().isCarActivity() ||
				FermataApplication.get().isMirroringMode();
	}

	/**
	 * Opens the Morphe YouTube app, with the given YouTube url, if not null.
	 */
	public static boolean openApp(@NonNull Context ctx, @Nullable String url) {
		Intent intent;

		if (url != null) {
			intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
			intent.setPackage(MORPHE_PKG);
		} else {
			intent = ctx.getPackageManager().getLaunchIntentForPackage(MORPHE_PKG);
			if (intent == null) return false;
		}

		intent.addFlags(FLAG_ACTIVITY_NEW_TASK);
		FermataApplication app = FermataApplication.get();
		if (app.isMirroringMode()) intent.putExtra(INTENT_EXTRA_MIRROR_MODE, app.getMirroringMode());

		try {
			ctx.startActivity(intent);
			return true;
		} catch (ActivityNotFoundException | SecurityException ex) {
			Log.e(ex, "Failed to open Morphe YouTube");
			return false;
		}
	}
}
