package me.aap.fermata.util;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

import java.io.File;

import me.aap.fermata.R;
import me.aap.utils.log.Log;
import me.aap.utils.pref.PreferenceStore;

/**
 * Receives the result of installing a Morphe YouTube update, see MorpheUpdater.
 */
public class MorpheInstallReceiver extends BroadcastReceiver {

	@SuppressWarnings("deprecation")
	@Override
	public void onReceive(Context ctx, Intent intent) {
		int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
		String tag = intent.getStringExtra(MorpheUpdater.EXTRA_TAG);
		PreferenceStore ps = MorpheYoutube.getPreferenceStore();

		if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
			// The first install, or an update of the app installed by another installer
			Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
			if (confirm == null) return;
			confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			try {
				ctx.startActivity(confirm);
			} catch (Exception ex) {
				Log.e(ex, "Failed to ask for the Morphe YouTube install confirmation");
				MorpheUpdater.toast(ctx, ctx.getString(R.string.morphe_update_failed, ex.getMessage()));
			}
			return;
		}

		deleteFile(intent);

		if (status == PackageInstaller.STATUS_SUCCESS) {
			Log.i("Morphe YouTube installed: ", tag);
			try (PreferenceStore.Edit e = ps.editPreferenceStore()) {
				e.setStringPref(MorpheUpdater.INSTALLED_TAG, tag);
				e.removePref(MorpheUpdater.FAILED_TAG);
			}
			MorpheUpdater.toast(ctx, ctx.getString(R.string.morphe_update_installed, tag));
			return;
		}

		String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
		Log.e("Failed to install Morphe YouTube ", tag, ": ", status, " ", msg);
		if (status == PackageInstaller.STATUS_FAILURE_ABORTED) return; // Cancelled by the user
		ps.applyStringPref(MorpheUpdater.FAILED_TAG, tag);

		if ((status == PackageInstaller.STATUS_FAILURE_CONFLICT) ||
				(status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE)) {
			MorpheUpdater.toast(ctx, ctx.getString(R.string.morphe_update_conflict));
		} else {
			MorpheUpdater.toast(ctx, ctx.getString(R.string.morphe_update_failed, msg));
		}
	}

	private static void deleteFile(Intent intent) {
		String path = intent.getStringExtra(MorpheUpdater.EXTRA_FILE);
		//noinspection ResultOfMethodCallIgnored
		if (path != null) new File(path).delete();
	}
}
