package org.hexian000.dynatweak;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.widget.*;

import java.util.Properties;

import static org.hexian000.dynatweak.DynatweakApp.LOG_TAG;

public class MainActivity extends Activity {
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
		if (service && DynatweakService.instance == null)
			startService(new Intent(this, DynatweakService.class));
		if (!service && DynatweakService.instance != null)
			stopService(new Intent(this, DynatweakService.class));

		if (onBoot && !BootReceiver.Fired) {
			BootReceiver.Fired = true;
			applySettings();
		}

		checkBootTweak.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
			@Override
			public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
				Properties config = ((DynatweakApp) getApplication()).getConfiguration();
				if (isChecked) {
					config.setProperty("smooth_interactive", "enabled");
				} else {
					config.setProperty("smooth_interactive", "disabled");
					Toast.makeText(MainActivity.this, R.string.reboot_suggest, Toast.LENGTH_SHORT).show();
				}
				((DynatweakApp) getApplication()).saveConfiguration();
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
		spinnerProfile.setSelection(Integer.parseInt(config.getProperty("interactive_profile", BootReceiver.PROFILE_BALANCED + "")));

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

		toggleService.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				Properties config = ((DynatweakApp) getApplication()).getConfiguration();
				Intent intent1 = new Intent(MainActivity.this, DynatweakService.class);
				if (toggleService.isChecked()) {
					startService(intent1);
					config.setProperty("dynatweak_service", "enabled");
				} else {
					stopService(intent1);
					config.setProperty("dynatweak_service", "disabled");
				}
				((DynatweakApp) getApplication()).saveConfiguration();
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
		final Properties config = ((DynatweakApp) getApplication()).getConfiguration();
		final int hotplug_profile = Integer.parseInt(config.getProperty("hotplug_profile", "0"));
		final int profile = Integer.parseInt(config.getProperty("interactive_profile", "0"));
		final TweakFinishedHandler handler = new TweakFinishedHandler(this);
		new Thread() {
			@Override
			public void run() {
				Message msg = new Message();
				try {
					BootReceiver.tweak(hotplug_profile, profile);
					msg.what = 0;
				} catch (Throwable e) {
					Log.e(LOG_TAG, "applySettings", e);
					msg.what = 1;
				}
				handler.sendMessage(msg);
			}
		}.start();
	}
}

class TweakFinishedHandler extends Handler {
	private final Context context;
	private boolean isBootTime = false;

	TweakFinishedHandler(MainActivity mainActivity) {
		this.context = mainActivity;
		final ProgressBar progressBar = mainActivity.findViewById(R.id.progressBar2);
		final Button buttonApply = mainActivity.findViewById(R.id.buttonApply);
		buttonApply.setEnabled(false);
		progressBar.setIndeterminate(true);
	}

	TweakFinishedHandler(Context context, boolean isBootTime) {
		this.context = context;
		this.isBootTime = isBootTime;
	}

	@Override
	public void handleMessage(Message msg) {
		if (isBootTime) {
			if (msg.what == 0) {
				Toast.makeText(context, R.string.boot_success, Toast.LENGTH_SHORT).show();
			} else {
				Toast.makeText(context, R.string.boot_failed, Toast.LENGTH_SHORT).show();
			}
		} else {
			MainActivity mainActivity = (MainActivity) context;
			if (msg.what == 0) {
				Toast.makeText(context, R.string.operation_success, Toast.LENGTH_SHORT).show();
			} else {
				Toast.makeText(context, R.string.operation_failed, Toast.LENGTH_SHORT).show();
			}
			final ProgressBar progressBar = mainActivity.findViewById(R.id.progressBar2);
			final Button buttonApply = mainActivity.findViewById(R.id.buttonApply);
			buttonApply.setEnabled(true);
			progressBar.setIndeterminate(false);
		}
		super.handleMessage(msg);
	}
}
