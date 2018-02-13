package org.hexian000.dynatweak;

import android.app.Application;
import android.util.Log;

import java.io.IOException;
import java.util.Properties;

public class DynatweakApp extends Application {
	static final String LOG_TAG = "Dynatweak";

	private static final String PREFERENCES_FILE_NAME = "preferences";
	private Properties configuration = null;

	void saveConfiguration() {
		try {
			configuration.store(getApplicationContext().
							openFileOutput(PREFERENCES_FILE_NAME, MODE_PRIVATE),
					null);
		} catch (IOException ex) {
			Log.wtf(LOG_TAG, "Error saving properties", ex);
		}
	}

	Properties getConfiguration() {
		return configuration;
	}

	private void loadConfiguration() {
		if (configuration == null) {
			configuration = new Properties();
			try {
				configuration.load(getApplicationContext().
						openFileInput("preferences"));
			} catch (IOException ex) {
				Log.wtf(LOG_TAG, "Error loading properties", ex);
			}
		}
	}

	@Override
	public void onCreate() {
		super.onCreate();
		loadConfiguration();
	}
}
