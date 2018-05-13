package org.hexian000.dynatweak;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.text.Html;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Timer;
import java.util.TimerTask;

import static org.hexian000.dynatweak.DynatweakApp.LOG_TAG;

/**
 * Created by hexian on 2017/6/18.
 * System monitor service
 */
public class MonitorService extends Service {
	public final static String CHANNEL_MONITOR = "monitor_overlay";

	static MonitorService instance = null;
	private final Handler handler = new Handler();
	private boolean visible = false;
	private Timer timer = null;
	private WindowManager windowManager = null;
	private MonitorOverlay monitorOverlay = null;
	private BroadcastReceiver eventListener = null;
	private DeviceInfo deviceInfo = null;

	private void showMonitor() {
		if (!visible) {
			try {
				createOverlay(this);
			} catch (WindowManager.BadTokenException e) {
				windowManager = null;
				monitorOverlay = null;
				Toast.makeText(getApplicationContext(), R.string.error_overlay_permission, Toast.LENGTH_SHORT).show();
				try {
					Intent intent2 = null;
					if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
						intent2 = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
					}
					startActivity(intent2);
				} catch (Throwable ignore) {
				}
			}
		}
	}

	private void hideMonitor() {
		if (visible) {
			removeOverlay();
		}
	}

	@Override
	public IBinder onBind(Intent intent) {
		throw new UnsupportedOperationException();
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		Notification.Builder builder = new Notification.Builder(this.getApplicationContext());
		builder.setContentIntent(null)
		       .setContentTitle(getResources().getString(R.string.monitor_overlay))
		       .setSmallIcon(R.drawable.ic_settings_black_24dp)
		       .setWhen(System.currentTimeMillis())
		       .setProgress(0, 0, true)
		       .setOngoing(true);

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
			builder.setVisibility(Notification.VISIBILITY_PUBLIC);
		}

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			// Android 8.0+
			NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
			if (manager != null) {
				NotificationChannel channel = new NotificationChannel(CHANNEL_MONITOR,
						getResources().getString(R.string.monitor_overlay), NotificationManager.IMPORTANCE_LOW);
				channel.enableLights(false);
				channel.enableVibration(false);
				channel.setSound(null, null);

				manager.createNotificationChannel(channel);
				builder.setChannelId(CHANNEL_MONITOR);
			}
		} else {
			builder.setPriority(Notification.PRIORITY_LOW)
			       .setLights(0, 0, 0)
			       .setVibrate(null)
			       .setSound(null);
		}

		startForeground(startId, builder.build());

		if (timer == null) {
			timer = new Timer();
			timer.scheduleAtFixedRate(new RefreshTask(), 0, 500);
		}
		if (eventListener == null) {
			eventListener = new BroadcastReceiver() {
				private String action = null;

				@Override
				public void onReceive(Context context, Intent intent) {
					action = intent.getAction();
					if (Intent.ACTION_SCREEN_ON.equals(action)) {
						if (timer == null) {
							timer = new Timer();
							timer.scheduleAtFixedRate(new RefreshTask(), 0, 500);
						}
					} else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
						timer.cancel();
						timer = null;
					}
				}
			};
			IntentFilter filter = new IntentFilter();
			filter.addAction(Intent.ACTION_SCREEN_ON);
			filter.addAction(Intent.ACTION_SCREEN_OFF);
			registerReceiver(eventListener, filter);
		}
		showMonitor();
		instance = this;
		return START_STICKY;
	}

	@Override
	public void onCreate() {
		super.onCreate();
		deviceInfo = new DeviceInfo(Kernel.getInstance());
	}

	@Override
	public void onDestroy() {
		hideMonitor();
		if (timer != null) {
			timer.cancel();
			timer = null;
		}
		if (eventListener != null) {
			unregisterReceiver(eventListener);
			eventListener = null;
		}
		if (windowManager != null) {
			removeOverlay();
		}
		if (deviceInfo != null) {
			deviceInfo = null;
		}
		instance = null;
		super.onDestroy();
	}

	private void updateOverlay() {
		if (monitorOverlay != null) {
			TextView textView = monitorOverlay.findViewById(R.id.textView);
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
				textView.setText(Html.fromHtml(deviceInfo.getHtml(), Html.FROM_HTML_MODE_COMPACT));
			} else {
				textView.setText(Html.fromHtml(deviceInfo.getHtml()));
			}
		}
	}

	private void createOverlay(Context context) throws WindowManager.BadTokenException {
		Point screenSize = new Point();
		windowManager = (WindowManager) getApplicationContext().getSystemService(Context.WINDOW_SERVICE);
		if (windowManager == null) {
			Log.e(LOG_TAG, "WindowManager is null");
			return;
		}
		windowManager.getDefaultDisplay().getSize(screenSize);

		monitorOverlay = new MonitorOverlay(context);

		WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams();
		if (Build.VERSION.SDK_INT >= 26) {
			layoutParams.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
		} else {
			layoutParams.type = WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY;
		}
		layoutParams.format = PixelFormat.RGBA_8888;
		layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
				| WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
		layoutParams.width = WindowManager.LayoutParams.WRAP_CONTENT;
		layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
		layoutParams.gravity = Gravity.END | Gravity.TOP;
		layoutParams.x = 0;
		layoutParams.y = 0;

		windowManager.addView(monitorOverlay, layoutParams);
		visible = true;
	}

	private void removeOverlay() {
		visible = false;
		if (monitorOverlay != null) {
			windowManager.removeView(monitorOverlay);
			windowManager = null;
			monitorOverlay = null;
		}
	}

	class RefreshTask extends TimerTask {
		@Override
		public void run() {
			handler.post(new Runnable() {
				@Override
				public synchronized void run() {
					if (visible) {
						deviceInfo.stat.sample();
						updateOverlay();
					}
				}
			});
		}

	}
}
