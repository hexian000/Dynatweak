package org.hexian000.dynatweak;

import android.content.Context;
import android.view.LayoutInflater;
import android.widget.LinearLayout;


class MonitorOverlay extends LinearLayout {

	public MonitorOverlay(Context context) {
		super(context);
		LayoutInflater.from(context).inflate(R.layout.overlay_monitor, this);
	}

}