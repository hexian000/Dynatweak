package org.hexian000.dynatweak.api;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.UUID;

public class Shell {
	private final boolean available;
	private Process shell = null;
	private PrintStream out = null;
	private Scanner in = null;

	public Shell(String command) {
		this(command, false);
	}

	public Shell(String command, boolean collectStderr) {
		try {
			shell = new ProcessBuilder(command)
					.redirectErrorStream(collectStderr)
					.start();
			in = new Scanner(shell.getInputStream());
			out = new PrintStream(shell.getOutputStream());
		} catch (IOException ignored) {
		}
		List<String> result = run("echo OK");
		available = result.size() > 0 && "OK".equals(result.get(0));
	}

	public boolean isAvailable() {
		return available;
	}

	public synchronized List<String> run(String command) {
		List<String> list = new ArrayList<>();
		if (shell == null || out == null || in == null) {
			return list;
		}
		String uuid = UUID.randomUUID().toString();
		out.println(command);
		out.println("echo; echo '" + uuid + "'");
		out.flush();
		while (true) {
			String line = in.nextLine();
			if (line.equals(uuid)) {
				return list;
			}
			list.add(line);
		}
	}

	public synchronized List<String> run(List<String> commands) {
		List<String> list = new ArrayList<>();
		if (shell == null || out == null || in == null) {
			return list;
		}
		String uuid = UUID.randomUUID().toString();
		for (String command : commands) {
			out.println(command);
		}
		out.println("echo; echo '" + uuid + "'");
		out.flush();
		while (true) {
			String line = in.nextLine();
			if (line.equals(uuid)) {
				return list;
			}
			list.add(line);
		}
	}

	public synchronized void close() {
		if (out != null) {
			out.println("exit");
			out.flush();
			out.close();
			out = null;
		}
		if (in != null) {
			in.close();
			in = null;
		}
		shell = null;
	}

	@Override
	protected void finalize() throws Throwable {
		close();
		super.finalize();
	}
}
