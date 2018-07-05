package org.hexian000.dynatweak.api;

import java.io.IOException;

public class MaxTempReader extends AdaptiveTempReader {
	private double max = 0;

	MaxTempReader(String node) throws IOException {
		super(node);
	}

	double read() throws IOException {
		double value = super.read();
		if (value > max) {
			max = value;
		}
		return max;
	}
}
