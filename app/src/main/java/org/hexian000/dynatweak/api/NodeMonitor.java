package org.hexian000.dynatweak.api;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.List;

public class NodeMonitor {
	private RandomAccessFile fr;
	private Shell su;
	private String cmd;
	private byte[] buf;

	public NodeMonitor(String node) throws IOException {
		fr = new RandomAccessFile(node, "r");
	}

	public NodeMonitor(String node, Shell root) {
		su = root;
		cmd = "cat '" + node + "'";
	}

	public String readLine() throws IOException {
		if (fr != null) {
			fr.seek(0);
			return fr.readLine();
		} else {
			List<String> out = su.run(cmd);
			if (out.size() > 0) {
				return out.get(0);
			}
		}
		throw new IOException("failed read node");
	}

	public String readAll() throws IOException {
		if (fr != null) {
			fr.seek(0);
			if (buf == null) {
				buf = new byte[4096];
			}
			int read = fr.read(buf);
			return new String(buf, 0, read);
		} else {
			List<String> out = su.run(cmd);
			StringBuilder sb = new StringBuilder();
			for (String line : out) {
				sb.append(line).append('\n');
			}
			return sb.toString();
		}
	}
}
