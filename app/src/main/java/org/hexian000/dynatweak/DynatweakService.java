package org.hexian000.dynatweak;

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

import java.io.IOException;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Created by hexian on 2017/6/18.
 * System monitor service
 */
public class DynatweakService extends Service {

	static DynatweakService instance = null;
	//	static boolean chargingOnly = false;
	private boolean thermal = false;
	private boolean visible = false;
	private int[] thermal_last_limits = null;
	private Kernel k;
	private Handler handler = new Handler();
	private Timer timer = null;
	private WindowManager windowManager = null;
	private MonitorOverlay monitorOverlay = null;
	private BroadcastReceiver eventListener = null;
	private DeviceInfo deviceInfo = null;

	void showMonitor() {
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

	void hideMonitor() {
		if (visible) removeOverlay();
	}

	void startThermal() throws IOException {
		if (!thermal) {
			int n = k.cpuCores.size();
			thermal_last_limits = new int[n];
			for (int i = 0; i < n; i++)
				thermal_last_limits[i] = -1;
			thermal = true;
		}
	}

	void stopThermal() {
		if (thermal) {
			thermal = false;
			thermal_last_limits = null;
			for (Kernel.CpuCore cpu : k.cpuCores) {
				try {
					cpu.setOnline(true, false);
					cpu.setScalingMaxFrequency(cpu.fitPercentage(1), false);
				} catch (Throwable ignore) {
				}
			}
		}
	}

	@Override
	public IBinder onBind(Intent intent) {
		throw new UnsupportedOperationException();
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
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
					}/* else if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
							if (chargingOnly) {
								int status = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
								if (status != 0) {
									if (!visible) createOverlay(DynatweakService.this);
								} else if (visible) removeOverlay();
							} else if (!visible) createOverlay(DynatweakService.this);
						}*/
				}
			};
			IntentFilter filter = new IntentFilter();
			filter.addAction(Intent.ACTION_SCREEN_ON);
			filter.addAction(Intent.ACTION_SCREEN_OFF);
//				filter.addAction(Intent.ACTION_BATTERY_CHANGED);
			registerReceiver(eventListener, filter);
		}
		MainActivity.loadProperties(this);
		boolean monitor = MainActivity.properties.getProperty("monitor_service", "disabled").equals("enabled");
		if (monitor) showMonitor();
		boolean supportThermal = k.cpuCores.get(0).hasTemperature();
		boolean thermal = MainActivity.properties.getProperty("thermal_service", "disabled").equals("enabled");
		if (supportThermal && thermal) try {
			startThermal();
		} catch (IOException e) {
			MainActivity.properties.setProperty("thermal_service", "disabled");
			MainActivity.saveProperties(this);
		}
		instance = this;
		return START_STICKY;
	}

	@Override
	public void onCreate() {
		super.onCreate();
		try {
			k = Kernel.getInstance();
			deviceInfo = new DeviceInfo(k);
		} catch (IOException e) {
			Log.e("Dynatweak", "Service create", e);
		}
	}

	@Override
	public void onDestroy() {
		hideMonitor();
		stopThermal();
		if (timer != null) {
			timer.cancel();
			timer = null;
		}
		if (eventListener != null) {
			unregisterReceiver(eventListener);
			eventListener = null;
		}
		if (windowManager != null)
			removeOverlay();
		if (deviceInfo != null) {
			deviceInfo = null;
		}
		instance = null;
		k.releaseRoot();
		super.onDestroy();
	}

	/*public class PluggedReceiver extends BroadcastReceiver {
		@Override
		public void onReceive(Context context, Intent intent) {
			if (DynatweakService.chargingOnly) {
				int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
				boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
						status == BatteryManager.BATTERY_STATUS_FULL;
				if (isCharging) {
					if (!visible) createOverlay(DynatweakService.this);
				} else {
					if (visible) removeOverlay();
				}
			}
		}
	}*/

	private void updateOverlay() {
		if (monitorOverlay != null) {
			TextView textView = (TextView) monitorOverlay.findViewById(R.id.textView);
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
				textView.setText(Html.fromHtml(deviceInfo.getHtml(), Html.FROM_HTML_MODE_COMPACT));
			} else {
				//noinspection deprecation
				textView.setText(Html.fromHtml(deviceInfo.getHtml()));
			}
		}
	}

	private void createOverlay(Context context) throws WindowManager.BadTokenException {
		Point screenSize = new Point();
		windowManager = (WindowManager) getApplicationContext().getSystemService(Context.WINDOW_SERVICE);
		windowManager.getDefaultDisplay().getSize(screenSize);

		monitorOverlay = new MonitorOverlay(context);

		WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams();
		layoutParams.type = WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY;
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
					if (thermal) {
						try {
							for (Kernel.CpuCore cpu : k.cpuCores) {
								int id = cpu.getId();
								double temp = cpu.getTemperature();
								if (cpu.isOnline()) {
									try {
										int freq = thermal_last_limits[id];
										if (temp >= 90 && id == 0) {
											freq = cpu.fitPercentage(0);
										}
										if (temp >= 80) {
											freq = cpu.fitPercentage(0.5);
										} else if (temp >= 70) {
											freq = cpu.fitPercentage(0.75);
										} else if (temp < 60) {
											freq = cpu.fitPercentage(1);
										}
										if (temp >= 90 && id != 0)
											cpu.setOnline(false, true);
										else if (freq != thermal_last_limits[id]) {
											cpu.setScalingMaxFrequency(freq, true);
											thermal_last_limits[id] = freq;
										}
									} catch (Throwable ignore) {
									}
								} else if (temp < 75 && id != 0) {
									cpu.setOnline(true, false);
								}
							}
						} catch (IOException e) {
							Log.wtf("Dynatweak", "Timer run", e);
						}
					}
				}
			});
		}

	}
}
