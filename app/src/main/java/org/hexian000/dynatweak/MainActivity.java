package org.hexian000.dynatweak;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.*;

import java.util.Properties;

import static org.hexian000.dynatweak.DynatweakApp.LOG_TAG;

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
			if (!k.cpuCores.get(0).getScalingAvailableGovernors().contains("interactive")) {
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
		Properties config = ((DynatweakApp) getApplication()).getConfiguration();
		boolean onBoot = config.getProperty("smooth_interactive", "disabled").equals("enabled");
		checkBootTweak.setChecked(onBoot);
		boolean service = config.getProperty("dynatweak_service", "disabled").equals("enabled");
		toggleService.setChecked(service);
		if (service && DynatweakService.instance == null) {
			startService(new Intent(this, DynatweakService.class));
		}
		if (!service && DynatweakService.instance != null) {
			stopService(new Intent(this, DynatweakService.class));
		}

		checkBootTweak.setOnCheckedChangeListener((buttonView, isChecked) -> {
			Properties config1 = ((DynatweakApp) getApplication()).getConfiguration();
			if (isChecked) {
				config1.setProperty("smooth_interactive", "enabled");
			} else {
				config1.setProperty("smooth_interactive", "disabled");
				Toast.makeText(MainActivity.this, R.string.reboot_suggest, Toast.LENGTH_SHORT).show();
			}
			((DynatweakApp) getApplication()).saveConfiguration();
		});

		// 事件响应
		spinnerProfile.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {

			@Override
			public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
				Properties config = ((DynatweakApp) getApplication()).getConfiguration();
				config.setProperty("interactive_profile", i + "");
				((DynatweakApp) getApplication()).saveConfiguration();
			}

			@Override
			public void onNothingSelected(AdapterView<?> adapterView) {
				Properties config = ((DynatweakApp) getApplication()).getConfiguration();
				config.setProperty("interactive_profile", BootReceiver.PROFILE_BALANCED + "");
				((DynatweakApp) getApplication()).saveConfiguration();
			}
		});
		spinnerProfile.setSelection(
				Integer.parseInt(config.getProperty("interactive_profile", BootReceiver.PROFILE_BALANCED + "")));

		spinnerHotplug.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
			boolean first = true;

			@Override
			public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
				Properties config = ((DynatweakApp) getApplication()).getConfiguration();
				if (first) {
					first = false;
					return;
				}
				if (i == 2) {
					config.setProperty("thermal_service", "disabled");
				}
				config.setProperty("hotplug_profile", i + "");
				((DynatweakApp) getApplication()).saveConfiguration();
			}

			@Override
			public void onNothingSelected(AdapterView<?> adapterView) {
				Properties config = ((DynatweakApp) getApplication()).getConfiguration();
				config.setProperty("hotplug_profile", "0");
				((DynatweakApp) getApplication()).saveConfiguration();
			}
		});
		spinnerHotplug.setSelection(Integer.parseInt(config.getProperty("hotplug_profile", "0")));

		toggleService.setOnClickListener(view -> {
			Properties config12 = ((DynatweakApp) getApplication()).getConfiguration();
			Intent intent1 = new Intent(MainActivity.this, DynatweakService.class);
			if (toggleService.isChecked()) {
				startService(intent1);
				config12.setProperty("dynatweak_service", "enabled");
			} else {
				stopService(intent1);
				config12.setProperty("dynatweak_service", "disabled");
			}
			((DynatweakApp) getApplication()).saveConfiguration();
		});

		buttonApply.setOnClickListener(view -> applySettings());
	}

	private void applySettings() {
		buttonApply.setEnabled(false);
		progressBar.setVisibility(View.VISIBLE);
		progressBar.setIndeterminate(true);
		final Properties config = ((DynatweakApp) getApplication()).getConfiguration();
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
