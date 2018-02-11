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
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.Spinner;
import android.widget.Toast;
import android.widget.ToggleButton;

import java.io.IOException;
import java.util.Properties;

import static org.hexian000.dynatweak.Kernel.LOG_TAG;

public class MainActivity extends Activity {
	private static final String PREFERENCES_FILE_NAME = "preferences";
	static Properties properties = null;
	private ToggleButton toggleService;
	private Spinner spinnerProfile, spinnerHotplug;

	static void loadProperties(Context context) {
		if (MainActivity.properties == null) {
			MainActivity.properties = new Properties();
			try {
				MainActivity.properties.load(context.openFileInput("preferences"));
			} catch (IOException ex) {
				Log.e(LOG_TAG, "Error loading properties", ex);
			}
		}
	}

	private static void saveProperties(Context context) {
		try {
			properties.store(context.openFileOutput(PREFERENCES_FILE_NAME, MODE_PRIVATE), null);
		} catch (IOException ex) {
			Log.e(LOG_TAG, "Error loading properties", ex);
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
			Log.d(LOG_TAG, "Unsupported", e);
			Toast.makeText(this, R.string.kernel_unsupported, Toast.LENGTH_SHORT).show();
			finish();
			return;
		}

		// 初始化控件
		CheckBox checkMasterSwitch = findViewById(R.id.checkMasterSwitch);
		spinnerProfile = findViewById(R.id.spinnerProfile);
		spinnerHotplug = findViewById(R.id.spinnerHotplug);
		toggleService = findViewById(R.id.toggleService);
		Button buttonApply = findViewById(R.id.buttonApply);

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

		checkMasterSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
			@Override
			public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
				spinnerHotplug.setEnabled(isChecked);
				spinnerProfile.setEnabled(isChecked);
				if (isChecked) {
					properties.setProperty("smooth_interactive", "enabled");
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
				saveProperties(MainActivity.this);
			}

			@Override
			public void onNothingSelected(AdapterView<?> adapterView) {
				properties.setProperty("interactive_profile", BootReceiver.PROFILE_BALANCED + "");
				saveProperties(MainActivity.this);
			}
		});
		spinnerProfile.setSelection(Integer.parseInt(properties.getProperty("interactive_profile", BootReceiver.PROFILE_BALANCED + "")));

		spinnerHotplug.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
			boolean first = true;

			@Override
			public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
				if (first) {
					first = false;
					return;
				}
				if (i == 2) {
					properties.setProperty("thermal_service", "disabled");
				}
				properties.setProperty("hotplug_profile", i + "");
				saveProperties(MainActivity.this);
			}

			@Override
			public void onNothingSelected(AdapterView<?> adapterView) {
				properties.setProperty("hotplug_profile", "0");
				saveProperties(MainActivity.this);
			}
		});
		spinnerHotplug.setSelection(Integer.parseInt(properties.getProperty("hotplug_profile", "0")));

		toggleService.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				Intent intent1 = new Intent(MainActivity.this, DynatweakService.class);
				if (toggleService.isChecked()) {
					startService(intent1);
					properties.setProperty("dynatweak_service", "enabled");
				} else {
					stopService(intent1);
					properties.setProperty("dynatweak_service", "disabled");
				}
				saveProperties(MainActivity.this);
			}
		});

		buttonApply.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				applySettings();
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
					Log.e(LOG_TAG, "MainActivity.applySettings()", e);
					msg.what = 1;
				}
				handler.sendMessage(msg);
			}
		}.start();
	}
}

class ProgressHandler extends Handler {
	private final Context context;
	private ProgressDialog tweaking;

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