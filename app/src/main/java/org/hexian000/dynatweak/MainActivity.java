package org.hexian000.dynatweak;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.*;
import org.hexian000.dynatweak.api.Kernel;

import java.util.Properties;

import static org.hexian000.dynatweak.Dynatweak.LOG_TAG;

public class MainActivity extends Activity {
	private ProgressBar progressBar;
	private Button buttonApply;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		progressBar = findViewById(R.id.progressBar2);
		buttonApply = findViewById(R.id.buttonApply);

		// 检查兼容性
		Kernel k;
		try {
			k = Kernel.getInstance();
			if (!k.getCpuCore(0).getScalingAvailableGovernors().contains("interactive")) {
				Toast.makeText(this, R.string.interactive_not_found, Toast.LENGTH_SHORT).show();
				finish();
				return;
			}
		} catch (Throwable e) {
			Log.wtf(LOG_TAG, "Unsupported", e);
			Toast.makeText(this, R.string.kernel_unsupported, Toast.LENGTH_SHORT).show();
			finish();
			return;
		}

		// 初始化控件
		final CheckBox checkBootTweak = findViewById(R.id.checkMasterSwitch);
		final Spinner spinnerProfile = findViewById(R.id.spinnerProfile);
		final Spinner spinnerHotplug = findViewById(R.id.spinnerHotplug);
		final ToggleButton toggleService = findViewById(R.id.toggleService);
		final Button buttonApply = findViewById(R.id.buttonApply);

		// 加载设置
		Properties config = ((Dynatweak) getApplication()).getConfiguration();
		boolean onBoot = config.getProperty("smooth_interactive", "disabled").equals("enabled");
		checkBootTweak.setChecked(onBoot);
		boolean service = config.getProperty("dynatweak_service", "disabled").equals("enabled");
		toggleService.setChecked(service);
		if (service && MonitorService.instance == null) {
			Intent intent1 = new Intent(this, MonitorService.class);
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				startForegroundService(intent1);
			} else {
				startService(intent1);
			}
		}
		if (!service && MonitorService.instance != null) {
			stopService(new Intent(this, MonitorService.class));
		}

		checkBootTweak.setOnCheckedChangeListener((buttonView, isChecked) -> {
			Properties config1 = ((Dynatweak) getApplication()).getConfiguration();
			if (isChecked) {
				config1.setProperty("smooth_interactive", "enabled");
			} else {
				config1.setProperty("smooth_interactive", "disabled");
				Toast.makeText(MainActivity.this, R.string.reboot_suggest, Toast.LENGTH_SHORT).show();
			}
			((Dynatweak) getApplication()).saveConfiguration();
		});

		// 事件响应
		spinnerProfile.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {

			@Override
			public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
				Properties config = ((Dynatweak) getApplication()).getConfiguration();
				config.setProperty("interactive_profile", i + "");
				((Dynatweak) getApplication()).saveConfiguration();
			}

			@Override
			public void onNothingSelected(AdapterView<?> adapterView) {
				Properties config = ((Dynatweak) getApplication()).getConfiguration();
				config.setProperty("interactive_profile", Dynatweak.Profiles.DEFAULT + "");
				((Dynatweak) getApplication()).saveConfiguration();
			}
		});
		spinnerProfile.setSelection(
				Integer.parseInt(config.getProperty("interactive_profile", Dynatweak.Profiles.DEFAULT + "")));

		spinnerHotplug.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
			boolean first = true;

			@Override
			public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
				Properties config = ((Dynatweak) getApplication()).getConfiguration();
				if (first) {
					first = false;
					return;
				}
				if (i == 2) {
					config.setProperty("thermal_service", "disabled");
				}
				config.setProperty("hotplug_profile", i + "");
				((Dynatweak) getApplication()).saveConfiguration();
			}

			@Override
			public void onNothingSelected(AdapterView<?> adapterView) {
				Properties config = ((Dynatweak) getApplication()).getConfiguration();
				config.setProperty("hotplug_profile", "0");
				((Dynatweak) getApplication()).saveConfiguration();
			}
		});
		spinnerHotplug.setSelection(Integer.parseInt(config.getProperty("hotplug_profile", "0")));

		toggleService.setOnClickListener(view -> {
			Properties configuration = ((Dynatweak) getApplication()).getConfiguration();
			Intent intent1 = new Intent(MainActivity.this, MonitorService.class);
			if (toggleService.isChecked()) {
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
					startForegroundService(intent1);
				} else {
					startService(intent1);
				}
				configuration.setProperty("dynatweak_service", "enabled");
			} else {
				stopService(intent1);
				configuration.setProperty("dynatweak_service", "disabled");
			}
			((Dynatweak) getApplication()).saveConfiguration();
		});

		buttonApply.setOnClickListener(view -> applySettings());
	}

	private void applySettings() {
		buttonApply.setEnabled(false);
		progressBar.setVisibility(View.VISIBLE);
		progressBar.setIndeterminate(true);
		final Properties config = ((Dynatweak) getApplication()).getConfiguration();
		final int hotplug_profile = Integer.parseInt(config.getProperty("hotplug_profile", "0"));
		final int profile = Integer.parseInt(config.getProperty("interactive_profile", "0"));
		final Handler handler = new Handler();
		new Thread(() -> {
			try {
				BootReceiver.tweak(hotplug_profile, profile);
				handler.post(
						() -> Toast.makeText(MainActivity.this, R.string.operation_success, Toast.LENGTH_SHORT).show());
			} catch (Throwable e) {
				Log.e(LOG_TAG, "applySettings", e);
				handler.post(
						() -> Toast.makeText(MainActivity.this, R.string.operation_failed, Toast.LENGTH_SHORT).show());
			} finally {
				handler.post(() -> {
					buttonApply.setEnabled(true);
					progressBar.setIndeterminate(false);
					progressBar.setVisibility(View.INVISIBLE);
				});
			}
		}).start();
	}
}
