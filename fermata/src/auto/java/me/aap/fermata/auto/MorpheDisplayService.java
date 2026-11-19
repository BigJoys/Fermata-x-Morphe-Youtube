package me.aap.fermata.auto;

import static android.os.Build.VERSION.SDK_INT;

import android.annotation.SuppressLint;
import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.hardware.input.InputManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The Shizuku user service: runs in a separate process with the shell's (adb) permissions, which
 * allow what apps can't do: create a trusted virtual display, start other apps on it and inject
 * input events into it. Like scrcpy's "new display" (https://github.com/Genymobile/scrcpy).
 * <p>
 * This is not an Android app process: only plain Java and the system services can be used here,
 * not the Fermata application or its classes depending on it.
 */
@Keep
@SuppressLint({"PrivateApi", "DiscouragedPrivateApi", "BlockedPrivateApi", "SoonBlockedPrivateApi"})
public class MorpheDisplayService extends IMorpheDisplay.Stub {
	private static final String TAG = "MorpheDisplay";
	private static final String SHELL_PKG = "com.android.shell";
	private static final int SHELL_UID = 2000; // Process.SHELL_UID
	// DisplayManager.VIRTUAL_DISPLAY_FLAG_* (some are hidden)
	private static final int FLAG_PUBLIC = 1;
	private static final int FLAG_PRESENTATION = 1 << 1;
	private static final int FLAG_OWN_CONTENT_ONLY = 1 << 3;
	private static final int FLAG_SUPPORTS_TOUCH = 1 << 6;
	private static final int FLAG_ROTATES_WITH_CONTENT = 1 << 7;
	private static final int FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 << 8;
	private static final int FLAG_TRUSTED = 1 << 10;
	private static final int FLAG_OWN_DISPLAY_GROUP = 1 << 11;
	private static final int FLAG_ALWAYS_UNLOCKED = 1 << 12;
	private static final int FLAG_TOUCH_FEEDBACK_DISABLED = 1 << 13;
	private static final int FLAG_OWN_FOCUS = 1 << 14;
	private static final int FLAG_DEVICE_DISPLAY_GROUP = 1 << 15;
	private static final int DISPLAY_IME_POLICY_LOCAL = 0;
	private final Context ctx;
	private VirtualDisplay vd;
	private InputManager inputManager;
	private Method injectInputEvent;
	private Method setDisplayId;

	// Shizuku < 13
	@Keep
	public MorpheDisplayService() {
		this(null);
	}

	// Shizuku 13+
	@Keep
	public MorpheDisplayService(@Nullable Context ctx) {
		this.ctx = new ShellContext((ctx != null) ? ctx : systemContext());
	}

	@Override
	public void destroy() {
		releaseDisplay();
		System.exit(0);
	}

	@Override
	public synchronized int createDisplay(Surface surface, int width, int height, int dpi) {
		if (vd != null) {
			vd.setSurface(surface);
			vd.resize(width, height, dpi);
			return vd.getDisplay().getDisplayId();
		}

		int flags = FLAG_PUBLIC | FLAG_PRESENTATION | FLAG_OWN_CONTENT_ONLY | FLAG_SUPPORTS_TOUCH |
				FLAG_ROTATES_WITH_CONTENT | FLAG_DESTROY_CONTENT_ON_REMOVAL;
		if (SDK_INT >= 33) {
			flags |= FLAG_TRUSTED | FLAG_OWN_DISPLAY_GROUP | FLAG_ALWAYS_UNLOCKED |
					FLAG_TOUCH_FEEDBACK_DISABLED;
			if (SDK_INT >= 34) flags |= FLAG_OWN_FOCUS | FLAG_DEVICE_DISPLAY_GROUP;
		}

		try {
			// A DisplayManager for the shell package: the display is created for the calling package
			Constructor<DisplayManager> c = DisplayManager.class.getDeclaredConstructor(Context.class);
			c.setAccessible(true);
			DisplayManager dm = c.newInstance(ctx);
			vd = dm.createVirtualDisplay("Fermata Morphe YouTube", width, height, dpi, surface, flags);
		} catch (Exception ex) {
			Log.e(TAG, "Failed to create the virtual display", ex);
			throw new IllegalStateException("Failed to create the virtual display: " + ex, ex);
		}

		int id = vd.getDisplay().getDisplayId();
		Log.i(TAG, "Virtual display created: " + width + 'x' + height + '/' + dpi + ", id=" + id);
		setImePolicy(id);
		return id;
	}

	@Override
	public synchronized void releaseDisplay() {
		if (vd == null) return;
		vd.release();
		vd = null;
	}

	@Override
	public String startActivity(String[] args) {
		int id;
		synchronized (this) {
			if (vd == null) return "No display";
			id = vd.getDisplay().getDisplayId();
		}

		List<String> cmd = new ArrayList<>();
		cmd.add("/system/bin/am");
		cmd.add("start");
		cmd.add("--display");
		cmd.add(String.valueOf(id));
		cmd.addAll(Arrays.asList(args));

		try {
			java.lang.Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
			String out = read(p.getInputStream());
			int code = p.waitFor();
			Log.i(TAG, cmd + ": " + code + ' ' + out);
			// am prints "Error: ..." and may still exit with 0
			if ((code != 0) || out.contains("Error")) return out.isEmpty() ? ("Exit code " + code) : out;
			return null;
		} catch (Exception ex) {
			Log.e(TAG, "Failed to start " + cmd, ex);
			return ex.toString();
		}
	}

	@Override
	public void injectMotionEvent(MotionEvent e) {
		e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
		inject(e);
		e.recycle();
	}

	@Override
	public void injectKeyEvent(int action, int keyCode) {
		long now = SystemClock.uptimeMillis();
		inject(new KeyEvent(now, now, action, keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
				InputDevice.SOURCE_KEYBOARD));
	}

	private void inject(InputEvent e) {
		int id;
		synchronized (this) {
			if (vd == null) return;
			id = vd.getDisplay().getDisplayId();
		}

		try {
			if (injectInputEvent == null) {
				inputManager = (InputManager) ctx.getSystemService(Context.INPUT_SERVICE);
				injectInputEvent =
						InputManager.class.getMethod("injectInputEvent", InputEvent.class, int.class);
				setDisplayId = InputEvent.class.getMethod("setDisplayId", int.class);
			}
			setDisplayId.invoke(e, id);
			injectInputEvent.invoke(inputManager, e, 0); // INJECT_INPUT_EVENT_MODE_ASYNC
		} catch (Exception ex) {
			Log.e(TAG, "Failed to inject " + e, ex);
		}
	}

	// Show the keyboard on this display, not on the phone
	private static void setImePolicy(int displayId) {
		if (SDK_INT < 31) return;
		try {
			Object binder = Class.forName("android.os.ServiceManager")
					.getMethod("getService", String.class).invoke(null, "window");
			Object wm = Class.forName("android.view.IWindowManager$Stub")
					.getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
			wm.getClass().getMethod("setDisplayImePolicy", int.class, int.class)
					.invoke(wm, displayId, DISPLAY_IME_POLICY_LOCAL);
		} catch (Exception ex) {
			Log.w(TAG, "Failed to set the IME policy", ex);
		}
	}

	private static Context systemContext() {
		try {
			Class<?> c = Class.forName("android.app.ActivityThread");
			Object at = c.getMethod("currentActivityThread").invoke(null);
			if (at == null) at = c.getMethod("systemMain").invoke(null);
			return (Context) c.getMethod("getSystemContext").invoke(at);
		} catch (Exception ex) {
			throw new IllegalStateException("Failed to get the system context", ex);
		}
	}

	private static String read(InputStream in) throws java.io.IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buf = new byte[1024];
		for (int n = in.read(buf); n != -1; n = in.read(buf)) out.write(buf, 0, n);
		return out.toString().trim();
	}

	// System services check that the calling package belongs to the calling uid: the shell's
	@SuppressLint("NewApi")
	private static final class ShellContext extends ContextWrapper {

		ShellContext(Context base) {
			super(base);
		}

		@Override
		public String getPackageName() {
			return SHELL_PKG;
		}

		@Override
		public String getOpPackageName() {
			return SHELL_PKG;
		}

		@Override
		public AttributionSource getAttributionSource() {
			if (SDK_INT < 31) return super.getAttributionSource();
			return new AttributionSource.Builder(SHELL_UID).setPackageName(SHELL_PKG).build();
		}

		@Override
		public Context getApplicationContext() {
			return this;
		}
	}
}
