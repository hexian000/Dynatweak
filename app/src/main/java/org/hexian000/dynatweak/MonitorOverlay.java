package org.hexian000.dynatweak;

import android.content.Context;
import android.content.res.Configuration;
import android.view.LayoutInflater;
import android.widget.LinearLayout;


public class MonitorOverlay extends LinearLayout {

	public MonitorOverlay(Context context) {
		super(context);
		LayoutInflater.from(context).inflate(R.layout.overlay_monitor, this);
	}

	@Override
	protected void onConfigurationChanged(Configuration newConfig) {
		super.onConfigurationChanged(newConfig);
	}
}