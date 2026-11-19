package me.aap.fermata.auto;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import me.aap.fermata.BuildConfig;
import me.aap.fermata.R;
import me.aap.utils.log.Log;
import rikka.shizuku.Shizuku;

/**
 * Connects to MorpheDisplayService, running with the shell's permissions via Shizuku
 * (https://shizuku.rikka.app): it shows Morphe YouTube on a virtual display, which the car screen
 * renders. Requires the Shizuku app, started via wireless debugging, and its permission for Fermata.
 */
final class MorpheDisplay {
	static final String SHIZUKU_PKG = "moe.shizuku.privileged.api";
	private static final int PERMISSION_REQUEST = 0x4d4f;
	private static final long BINDER_TIMEOUT = 5000;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final Context ctx;
	private final Shizuku.UserServiceArgs args;
	private Callback callback;
	private IMorpheDisplay service;
	private boolean bound;

	private final Shizuku.OnBinderReceivedListener binderListener = this::onBinderReceived;
	private final Shizuku.OnRequestPermissionResultListener permissionListener =
			this::onPermissionResult;
	private final ServiceConnection connection = new ServiceConnection() {
		@Override
		public void onServiceConnected(ComponentName name, IBinder binder) {
			if ((binder == null) || !binder.pingBinder()) {
				fail(R.string.morphe_car_shizuku_failed);
				return;
			}
			service = IMorpheDisplay.Stub.asInterface(binder);
			Log.i("Morphe display service connected");
			Callback cb = callback;
			if (cb != null) cb.onConnected(service);
		}

		@Override
		public void onServiceDisconnected(ComponentName name) {
			Log.i("Morphe display service disconnected");
			service = null;
			fail(R.string.morphe_car_shizuku_failed);
		}
	};
	private final Runnable binderTimeout = () -> {
		Shizuku.removeBinderReceivedListener(binderListener);
		fail(isShizukuInstalled() ? R.string.morphe_car_shizuku_not_running :
				R.string.morphe_car_shizuku_not_installed);
	};

	interface Callback {
		void onConnected(@NonNull IMorpheDisplay display);

		void onError(@StringRes int msg);
	}

	MorpheDisplay(@NonNull Context ctx) {
		this.ctx = ctx.getApplicationContext();
		args = new Shizuku.UserServiceArgs(
				new ComponentName(this.ctx.getPackageName(), MorpheDisplayService.class.getName()))
				.daemon(false).tag("fermata_morphe_display").processNameSuffix("morphe")
				.debuggable(BuildConfig.DEBUG).version(BuildConfig.VERSION_CODE);
	}

	@Nullable
	IMorpheDisplay getService() {
		return service;
	}

	void connect(@NonNull Callback cb) {
		callback = cb;
		if (service != null) {
			cb.onConnected(service);
			return;
		}
		if (Shizuku.pingBinder()) {
			onBinderReceived();
		} else {
			// The binder is delivered by ShizukuProvider, soon after the app starts
			handler.postDelayed(binderTimeout, BINDER_TIMEOUT);
			Shizuku.addBinderReceivedListenerSticky(binderListener, handler);
		}
	}

	void disconnect() {
		callback = null;
		handler.removeCallbacks(binderTimeout);
		Shizuku.removeBinderReceivedListener(binderListener);
		Shizuku.removeRequestPermissionResultListener(permissionListener);
		IMorpheDisplay s = service;
		service = null;
		if (s != null) {
			try {
				s.releaseDisplay();
			} catch (Exception ex) {
				Log.d(ex);
			}
		}
		if (bound) {
			bound = false;
			try {
				// Also stops the service process, see MorpheDisplayService.destroy()
				Shizuku.unbindUserService(args, connection, true);
			} catch (Exception ex) {
				Log.d(ex);
			}
		}
	}

	private void onBinderReceived() {
		handler.removeCallbacks(binderTimeout);
		Shizuku.removeBinderReceivedListener(binderListener);
		if (callback == null) return;

		try {
			if (Shizuku.isPreV11() || (Shizuku.getVersion() < 11)) {
				fail(R.string.morphe_car_shizuku_old);
			} else if (Shizuku.checkSelfPermission() == PERMISSION_GRANTED) {
				bind();
			} else if (Shizuku.shouldShowRequestPermissionRationale()) {
				// Denied with "don't ask again"
				fail(R.string.morphe_car_shizuku_denied);
			} else {
				Shizuku.addRequestPermissionResultListener(permissionListener, handler);
				Shizuku.requestPermission(PERMISSION_REQUEST);
				Callback cb = callback;
				if (cb != null) cb.onError(R.string.morphe_car_shizuku_allow);
			}
		} catch (Exception ex) {
			Log.e(ex, "Shizuku failed");
			fail(R.string.morphe_car_shizuku_failed);
		}
	}

	private void onPermissionResult(int requestCode, int result) {
		if (requestCode != PERMISSION_REQUEST) return;
		Shizuku.removeRequestPermissionResultListener(permissionListener);
		if (callback == null) return;
		if (result == PERMISSION_GRANTED) bind();
		else fail(R.string.morphe_car_shizuku_denied);
	}

	private void bind() {
		try {
			bound = true;
			Shizuku.bindUserService(args, connection);
		} catch (Exception ex) {
			Log.e(ex, "Failed to start the Morphe display service");
			fail(R.string.morphe_car_shizuku_failed);
		}
	}

	private void fail(@StringRes int msg) {
		Callback cb = callback;
		if (cb != null) cb.onError(msg);
	}

	private boolean isShizukuInstalled() {
		try {
			ctx.getPackageManager().getPackageInfo(SHIZUKU_PKG, 0);
			return true;
		} catch (PackageManager.NameNotFoundException ex) {
			return false;
		}
	}
}
