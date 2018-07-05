package org.hexian000.dynatweak.api;

import android.util.Log;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;

import static org.hexian000.dynatweak.Dynatweak.LOG_TAG;

public class AdaptiveTempReader {
	private double divider;
	private RandomAccessFile fr;

	AdaptiveTempReader(String node) throws FileNotFoundException {
		try {
			fr = new RandomAccessFile(node, "r");
			divider = 1.0;
			read();
		} catch (Throwable e) {
			Log.w(LOG_TAG, "AdaptiveTempReader node=" + node, e);
			throw new FileNotFoundException();
		}
	}

	double read() throws IOException {
		fr.seek(0);
		int raw = Integer.parseInt(fr.readLine());
		double value = raw / divider;
		while (Math.abs(value) >= 130.0) {
			divider *= 10.0;
			value = raw / divider;
		}
		return value;
	}
}
