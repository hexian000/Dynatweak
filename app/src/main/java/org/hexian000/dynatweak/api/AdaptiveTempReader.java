package org.hexian000.dynatweak.api;

import android.util.Log;

import java.io.IOException;

import static org.hexian000.dynatweak.Dynatweak.LOG_TAG;

public class AdaptiveTempReader {
	private double divider;
	private NodeMonitor node;

	AdaptiveTempReader(String node) throws IOException {
		divider = 1.0;
		try {
			this.node = new NodeMonitor(node);
			read(); // fail early
		} catch (IOException e) {
			Log.w(LOG_TAG, "AdaptiveTempReader node=" + node, e);
			throw e;
		}
	}

	double read() throws IOException {
		int raw = Integer.parseInt(node.read());
		double value = raw / divider;
		while (Math.abs(value) >= 130.0) {
			divider *= 10.0;
			value = raw / divider;
		}
		return value;
	}
}
