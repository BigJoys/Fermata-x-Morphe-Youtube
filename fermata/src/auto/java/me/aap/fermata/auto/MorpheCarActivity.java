package me.aap.fermata.auto;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static me.aap.utils.ui.UiUtils.toIntPx;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.appcompat.widget.AppCompatImageView;

import com.google.android.apps.auto.sdk.CarActivity;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.R;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.util.MorpheYoutube;
import me.aap.utils.log.Log;

/**
 * The "Morphe YouTube" car app: shows Morphe YouTube, running on a virtual display (see
 * MorpheDisplayService), on the car screen and passes the car's touch and key events to it. Unlike
 * mirroring, the phone's own screen is neither used nor shown.
 */
public class MorpheCarActivity extends CarActivity implements SurfaceHolder.Callback,
		MorpheDisplay.Callback {
	private MorpheDisplay display;
	private Surface surface;
	private int width;
	private int height;
	private int dpi;
	private boolean started;
	@StringRes
	private int msg = R.string.morphe_car_connecting;

	@SuppressLint("ClickableViewAccessibility")
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		MainActivityDelegate.setTheme(this, true);
		display = new MorpheDisplay(this);
		dpi = getResources().getDisplayMetrics().densityDpi;

		var s = new SurfaceView(this);
		s.setLayoutParams(new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
		s.getHolder().addCallback(this);
		var root = new FrameLayout(this) {
			@Override
			public boolean onTouchEvent(MotionEvent e) {
				var d = display.getService();
				if (d == null) {
					// E.g. tapped to retry after starting Shizuku
					if (e.getAction() == MotionEvent.ACTION_UP) connect();
					return true;
				}
				try {
					d.injectMotionEvent(MotionEvent.obtain(e));
				} catch (Exception ex) {
					Log.d(ex);
				}
				return true;
			}
		};
		root.addView(s);
		root.addView(createButtons());
		setContentView(root);
	}

	@Override
	public void onDestroy() {
		// Android Auto disconnected or the app was closed: removes the display and Morphe on it
		display.disconnect();
		super.onDestroy();
	}

	@Override
	public void surfaceCreated(@NonNull SurfaceHolder h) {}

	@Override
	public void surfaceChanged(@NonNull SurfaceHolder h, int format, int width, int height) {
		surface = h.getSurface();
		this.width = width;
		this.height = height;
		if (display.getService() == null) {
			drawMsg(msg);
			connect();
		} else {
			showDisplay();
		}
	}

	@Override
	public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
		surface = null;
		// Keep the display and Morphe running, e.g. while the car shows the navigation
		var d = display.getService();
		if (d == null) return;
		try {
			d.createDisplay(null, width, height, dpi);
		} catch (Exception ex) {
			Log.d(ex);
		}
	}

	@Override
	public void onConnected(@NonNull IMorpheDisplay d) {
		showDisplay();
	}

	@Override
	public void onError(@StringRes int msg) {
		this.msg = msg;
		started = false;
		drawMsg(msg);
	}

	@Override
	public boolean onKeyDown(int keyCode, KeyEvent e) {
		return forwardKey(keyCode, e) || super.onKeyDown(keyCode, e);
	}

	@Override
	public boolean onKeyUp(int keyCode, KeyEvent e) {
		return forwardKey(keyCode, e) || super.onKeyUp(keyCode, e);
	}

	private void connect() {
		if (!MorpheYoutube.isInstalled(this)) {
			onError(R.string.morphe_car_not_installed);
			return;
		}
		drawMsg(R.string.morphe_car_connecting);
		display.connect(this);
	}

	private void showDisplay() {
		var d = display.getService();
		if ((d == null) || (surface == null)) return;
		try {
			d.createDisplay(surface, width, height, dpi);
			if (!started) started = startMorphe(null);
		} catch (Exception ex) {
			Log.e(ex, "Failed to show Morphe YouTube");
			onError(R.string.morphe_car_shizuku_failed);
		}
	}

	// Starts Morphe YouTube on the display, or brings it to the front
	private boolean startMorphe(String url) {
		var d = display.getService();
		if (d == null) return false;
		String[] args;

		if (url != null) {
			args = new String[]{"-a", Intent.ACTION_VIEW, "-d", url, "-p", MorpheYoutube.MORPHE_PKG};
		} else {
			var i = getPackageManager().getLaunchIntentForPackage(MorpheYoutube.MORPHE_PKG);
			if ((i == null) || (i.getComponent() == null)) {
				onError(R.string.morphe_car_not_installed);
				return false;
			}
			args = new String[]{"-n", i.getComponent().flattenToShortString()};
		}

		try {
			var err = d.startActivity(args);
			if (err == null) return true;
			Log.e("Failed to start Morphe YouTube: ", err);
		} catch (Exception ex) {
			Log.e(ex, "Failed to start Morphe YouTube");
		}
		onError(R.string.morphe_car_start_failed);
		return false;
	}

	private boolean forwardKey(int keyCode, KeyEvent e) {
		switch (keyCode) {
			case KeyEvent.KEYCODE_MEDIA_PLAY:
			case KeyEvent.KEYCODE_MEDIA_PAUSE:
			case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
			case KeyEvent.KEYCODE_MEDIA_NEXT:
			case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
			case KeyEvent.KEYCODE_MEDIA_STOP:
			case KeyEvent.KEYCODE_VOLUME_UP:
			case KeyEvent.KEYCODE_VOLUME_DOWN:
			case KeyEvent.KEYCODE_VOLUME_MUTE:
				return false; // Media session and volume
		}
		var d = display.getService();
		if (d == null) return false;
		try {
			d.injectKeyEvent(e.getAction(), keyCode);
			return true;
		} catch (Exception ex) {
			Log.d(ex);
			return false;
		}
	}

	private void key(int keyCode) {
		var d = display.getService();
		if (d == null) return;
		try {
			d.injectKeyEvent(KeyEvent.ACTION_DOWN, keyCode);
			d.injectKeyEvent(KeyEvent.ACTION_UP, keyCode);
		} catch (Exception ex) {
			Log.d(ex);
		}
	}

	// Back and Morphe home: the virtual display has no navigation bar
	private View createButtons() {
		var ll = new LinearLayout(this);
		ll.setOrientation(LinearLayout.VERTICAL);
		var lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
				FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.BOTTOM);
		int m = toIntPx(this, 8);
		lp.setMargins(m, m, m, m);
		ll.setLayoutParams(lp);
		ll.addView(button(R.drawable.back, () -> key(KeyEvent.KEYCODE_BACK)));
		ll.addView(button(R.drawable.home, () -> {
			if (display.getService() == null) connect();
			else startMorphe(null);
		}));
		return ll;
	}

	private View button(@DrawableRes int icon, Runnable onClick) {
		var v = new AppCompatImageView(this);
		int size = toIntPx(this, 40);
		int pad = toIntPx(this, 8);
		var lp = new LinearLayout.LayoutParams(size, size);
		lp.setMargins(0, pad / 2, 0, pad / 2);
		v.setLayoutParams(lp);
		v.setPadding(pad, pad, pad, pad);
		v.setImageResource(icon);
		v.setBackgroundResource(R.drawable.oval_bg);
		v.setAlpha(0.6f);
		v.setOnClickListener(x -> onClick.run());
		return v;
	}

	private void drawMsg(@StringRes int msg) {
		var s = surface;
		// Once Morphe is shown, the display draws on the surface
		if ((s == null) || !s.isValid() || started) return;
		try {
			var c = s.lockCanvas(null);
			var p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
			p.setColor(Color.WHITE);
			p.setTypeface(Typeface.DEFAULT);
			p.setTextAlign(Paint.Align.CENTER);
			p.setTextSize(height / 16f);
			var text = FermataApplication.get().getString(msg);
			int w = width * 9 / 10;
			var sl = StaticLayout.Builder.obtain(text, 0, text.length(), p, w).setMaxLines(8)
					.setEllipsize(TextUtils.TruncateAt.END).build();
			c.drawColor(Color.BLACK);
			c.translate(width / 2f, height / 2f - sl.getHeight() / 2f);
			sl.draw(c);
			s.unlockCanvasAndPost(c);
		} catch (Exception ex) {
			Log.d(ex, "Failed to draw message");
		}
	}
}
