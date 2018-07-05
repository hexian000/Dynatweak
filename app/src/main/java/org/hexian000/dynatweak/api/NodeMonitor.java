package org.hexian000.dynatweak.api;

import java.io.IOException;
import java.io.RandomAccessFile;

public class NodeMonitor {
	private RandomAccessFile fr;

	public NodeMonitor(String node) throws IOException {
		fr = new RandomAccessFile(node, "r");
	}

	public String read() throws IOException {
		fr.seek(0);
		return fr.readLine();
	}
}
