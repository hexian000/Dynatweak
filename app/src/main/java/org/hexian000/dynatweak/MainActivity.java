package org.hexian000.dynatweak;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.widget.*;

import java.io.IOException;
import java.util.Properties;

public class MainActivity extends Activity {
	private static final String PREFERENCES_FILE_NAME = "preferences";
	static Properties properties = null;
	private ToggleButton toggleService, toggleMonitor, toggleThermal;
	private Spinner spinnerProfile, spinnerHotplug;

	static void loadProperties(Context context) {
		if (MainActivity.properties == null) {
			MainActivity.properties = new Properties();
			try {
				MainActivity.properties.load(context.openFileInput("preferences"));
			} catch (IOException ignore) {
			}
		}
	}

	static void saveProperties(Context context) {
		try {
			properties.store(context.openFileOutput(PREFERENCES_FILE_NAME, MODE_PRIVATE), null);
		} catch (IOException ignore) {
		}
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		// 检查兼容性
		Kernel k;
		try {
			k = Kernel.getInstance();
			if (!k.cpuCores.get(0).getScalingAvailableGovernors().contains("interactive")) {
				Toast.makeText(this, R.string.interactive_not_found, Toast.LENGTH_SHORT).show();
				finish();
				return;
			}
		} catch (Throwable e) {
			Log.d(Kernel.LOG_TAG, "Unsupported", e);
			Toast.makeText(this, R.string.kernel_unsupported, Toast.LENGTH_SHORT).show();
			finish();
			return;
		}
		final boolean supportThermal = k.cpuCores.get(0).hasTemperature();

		// 初始化控件
		CheckBox checkMasterSwitch = (CheckBox) findViewById(R.id.checkMasterSwitch);
		spinnerProfile = (Spinner) findViewById(R.id.spinnerProfile);
		spinnerHotplug = (Spinner) findViewById(R.id.spinnerHotplug);
		toggleService = (ToggleButton) findViewById(R.id.toggleService);
		toggleMonitor = (ToggleButton) findViewById(R.id.toggleMonitor);
		toggleThermal = (ToggleButton) findViewById(R.id.toggleThermal);

		// 加载设置
		loadProperties(this);
		boolean master = properties.getProperty("smooth_interactive", "disabled").equals("enabled");
		checkMasterSwitch.setChecked(master);
		spinnerHotplug.setEnabled(master);
		spinnerProfile.setEnabled(master);
		boolean service = properties.getProperty("dynatweak_service", "disabled").equals("enabled");
		toggleService.setChecked(service);
		if (service && DynatweakService.instance == null)
			startService(new Intent(this, DynatweakService.class));
		if (!service && DynatweakService.instance != null)
			stopService(new Intent(this, DynatweakService.class));
		boolean monitor = properties.getProperty("monitor_service", "disabled").equals("enabled");
		toggleMonitor.setChecked(monitor);
		boolean thermal = properties.getProperty("thermal_service", "disabled").equals("enabled");
		toggleThermal.setChecked(thermal);
		toggleThermal.setEnabled(supportThermal);

		checkMasterSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
			@Override
			public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
				spinnerHotplug.setEnabled(isChecked);
				spinnerProfile.setEnabled(isChecked);
				if (isChecked) {
					properties.setProperty("smooth_interactive", "enabled");
					applySettings();
				} else {
					properties.setProperty("smooth_interactive", "disabled");
					Toast.makeText(MainActivity.this, R.string.reboot_suggest, Toast.LENGTH_SHORT).show();
				}
				saveProperties(MainActivity.this);
			}
		});

		// 事件响应
		spinnerProfile.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
			boolean first = true;

			@Override
			public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
				if (first) {
					first = false;
					return;
				}
				properties.setProperty("interactive_profile", i + "");
				applySettings();
				saveProperties(MainActivity.this);
			}

			@Override
			public void onNothingSelected(AdapterView<?> adapterView) {
				properties.setProperty("interactive_profile", "1");
				applySettings();
				saveProperties(MainActivity.this);
			}
		});
		spinnerProfile.setSelection(Integer.parseInt(properties.getProperty("interactive_profile", "1")));

		spinnerHotplug.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
			boolean first = true;

			@Override
			public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
				if (first) {
					first = false;
					return;
				}
				if (i == 2) {
					if (DynatweakService.instance != null)
						DynatweakService.instance.stopThermal();
					properties.setProperty("thermal_service", "disabled");
				}
				properties.setProperty("hotplug_profile", i + "");
				applySettings();
				saveProperties(MainActivity.this);
			}

			@Override
			public void onNothingSelected(AdapterView<?> adapterView) {
				properties.setProperty("hotplug_profile", "0");
				applySettings();
				saveProperties(MainActivity.this);
			}
		});
		spinnerHotplug.setSelection(Integer.parseInt(properties.getProperty("hotplug_profile", "0")));

		toggleService.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				Intent intent1 = new Intent(MainActivity.this, DynatweakService.class);
				if (toggleService.isChecked()) {
					toggleMonitor.setEnabled(true);
					toggleThermal.setEnabled(supportThermal);
					startService(intent1);
					properties.setProperty("dynatweak_service", "enabled");
				} else {
					stopService(intent1);
					toggleMonitor.setEnabled(false);
					toggleThermal.setEnabled(false);
					properties.setProperty("dynatweak_service", "disabled");
				}
				saveProperties(MainActivity.this);
			}
		});

		toggleMonitor.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				if (toggleMonitor.isChecked()) {
					if (DynatweakService.instance != null)
						DynatweakService.instance.showMonitor();
					properties.setProperty("monitor_service", "enabled");
				} else {
					if (DynatweakService.instance != null)
						DynatweakService.instance.hideMonitor();
					properties.setProperty("monitor_service", "disabled");
				}
				saveProperties(MainActivity.this);
			}
		});

		toggleThermal.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				if (toggleThermal.isChecked()) {
					if (supportThermal) {
						if (spinnerHotplug.getSelectedItemPosition() == 2) {
							spinnerHotplug.setSelection(0, true);
							properties.setProperty("hotplug_profile", "0");
							applySettings();
						}
						try {
							if (DynatweakService.instance != null)
								DynatweakService.instance.startThermal();
						} catch (IOException e) {
							Toast.makeText(MainActivity.this, R.string.boot_exception, Toast.LENGTH_SHORT).show();
							toggleThermal.setChecked(false);
						}
						properties.setProperty("thermal_service", "enabled");
					}
				} else {
					if (DynatweakService.instance != null)
						DynatweakService.instance.stopThermal();
					properties.setProperty("thermal_service", "disabled");
				}
				saveProperties(MainActivity.this);
			}
		});
	}

	private void applySettings() {
		final int hotplug_profile = Integer.parseInt(properties.getProperty("hotplug_profile", "0"));
		final int profile = Integer.parseInt(properties.getProperty("interactive_profile", "0"));
		final ProgressHandler handler = new ProgressHandler(this);
		new Thread() {
			@Override
			public void run() {
				Message msg = new Message();
				try {
					BootReceiver.tweak(hotplug_profile, profile);
					msg.what = 0;
				} catch (Throwable e) {
					Log.e(Kernel.LOG_TAG, "MainActivity.applySettings()", e);
					msg.what = 1;
				}
				handler.sendMessage(msg);
			}
		}.start();
	}
}

class ProgressHandler extends Handler {
	private ProgressDialog tweaking;
	private Context context;

	ProgressHandler(Context context) {
		tweaking = ProgressDialog.show(context, "正在应用设置", "请稍候...");
		tweaking.show();
		this.context = context;
	}

	@Override
	public void handleMessage(Message msg) {
		if (msg.what == 0) {
			Toast.makeText(context, R.string.operation_success, Toast.LENGTH_SHORT).show();
		} else {
			Toast.makeText(context, R.string.operation_failed, Toast.LENGTH_SHORT).show();
		}
		tweaking.dismiss();
		tweaking = null;
		super.handleMessage(msg);
	}
}