package org.hexian000.dynatweak;

import android.util.Log;

import java.io.*;
import java.util.*;

/**
 * Created by hexian on 2017/6/18.
 * Kernel interface
 */
class Kernel {

	private static Kernel instance = null;

	static Kernel getInstance() throws IOException {
		if (instance == null) instance = new Kernel();
		return instance;
	}

	private Process root;
	// private InputStream stdout, stderr;
	private PrintStream exec;
	private boolean useBusybox;
	private int raw_id;
	List<CpuCore> cpuCores;

	boolean hasNode(String path) {
		return new File(path).exists();
	}

	String getThermalZone(int id) {
		return "/sys/class/thermal/thermal_zone" + id + "/temp";
	}

	private AdaptiveTempReader socTemp = null, batteryTemp = null, gpuTemp = null;

	boolean hasSocTemperature() {
		return socTemp != null;
	}

	double getSocTemperature() {
		try {
			return socTemp.read();
		} catch (Throwable e) {
			socTemp = null;
			return 0;
		}
	}

	boolean hasBatteryTemperature() {
		return batteryTemp != null;
	}

	List<String> listBlockDevices() {
		final String blockPath = "/sys/block";
		List<String> ret = new ArrayList<>();
		try {
			File block = new File(blockPath);
			String list[] = block.list();
			for (String i : list) {
				if (!(i.startsWith("ram") || i.startsWith("zram") || i.startsWith("loop") || i.startsWith("dm-"))) {
					ret.add(blockPath + "/" + i);
				}
			}
		} catch (Throwable ignore) {
		}
		return ret;
	}

	List<String> listBlockAvailableScheduler(String node) {
		List<String> ret = new ArrayList<>();
		try {
			Scanner sc = new Scanner(new File(node));
			while (sc.hasNext()) {
				String scheduler = sc.next().trim();
				if (scheduler.startsWith("[") && scheduler.endsWith("]")) {
					ret.add(scheduler.substring(1, scheduler.length() - 3));
				} else {
					ret.add(scheduler);
				}
			}
		} catch (Throwable ignore) {
		}
		return ret;
	}

	double getBatteryTemperature() {
		try {
			return batteryTemp.read();
		} catch (Throwable e) {
			batteryTemp = null;
			return 0;
		}
	}

	boolean hasGpuTemperature() {
		return gpuTemp != null;
	}

	double getGpuTemperature() {
		try {
			return gpuTemp.read();
		} catch (Throwable e) {
			gpuTemp = null;
			return 0;
		}
	}

	private List<ClusterPolicy> clusterPolicies;

	List<ClusterPolicy> getAllPolicies() {
		return clusterPolicies;
	}

	boolean hasCoreControl() {
		return hasNode("/sys/module/msm_thermal/core_control/enabled") || hasNode("/sys/module/msm_thermal/core_control/cpus_offlined");
	}

	void setCoreControlMask(int mask) throws IOException {
		if (mask > 0) {
			setNode("/sys/module/msm_thermal/core_control/enabled", "1", true);
			setNode("/sys/module/msm_thermal/core_control/cpus_offlined", mask + "", true);
		} else {
			setNode("/sys/module/msm_thermal/core_control/enabled", "0", true);
		}
	}

	void grantRead(String path) {
		changeMode(path, "a+r");
	}

	void changeMode(String path, String permission) {
		try {
			runAsRoot("chmod " + permission + " \'" + path + "\'");
		} catch (IOException e) {
			Log.w("Dynatweak", "chmod failed", e);
		}
	}

	String readNode(String path) throws IOException {
		return new BufferedReader(new FileReader(path)).readLine();
	}

	void setNode(String path, String value) throws IOException {
		if (hasNode(path)) {
			runAsRoot("echo '" + value + "'>'" + path + "'\n");
		}
	}

	void setNode(String path, String value, boolean lock) throws IOException {
		if (hasNode(path)) {
			if (lock) {
				changeMode(path, "u+w");
				setNode(path, value);
				changeMode(path, "a-w");
			} else {
				changeMode(path, "u+w");
				setNode(path, value);
			}
		}
	}

	boolean trySetNode(String node, String value) {
		if (hasNode(node)) {
			try {
				setNode(node, value, false);
				return readNode(node).equals(value);
			} catch (IOException e) {
				Log.w("Dynatweak", "trySetNode failed", e);
				return false;
			}
		}
		return false;
	}

	void setSysctl(String node, String value) {
		try {
			if (!trySetNode("/proc/sys/" + node.replace('.', '/'), value))
				runAsRoot("sysctl -w " + node + "=" + value);
		} catch (IOException e) {
			Log.w("Dynatweak", "setSysctl failed", e);
		}
	}

	void runAsRoot(String command) throws IOException {
		// Log.d("Dynatweak", command);
		acquireRoot();
		exec.println(command);
		exec.flush();
	}

	private void acquireRoot() throws IOException {
		if (root == null) {
			root = Runtime.getRuntime().exec("su");
			exec = new PrintStream(root.getOutputStream());

			// Check busybox
			InputStream stdout = root.getInputStream();
			exec.println("busybox --list");
			exec.println("echo -END-");
			exec.println("exit");
			exec.flush();
			try {
				root.waitFor();
			} catch (InterruptedException ignored) {
			}
			BufferedReader isr = new BufferedReader(new InputStreamReader(stdout));
			HashSet<String> applets = new HashSet<>();
			while (stdout.available() > 0) {
				String line = isr.readLine();
				if (line.equals("-END-"))
					break;
				applets.add(line);
			}
			useBusybox = true;
			do {
				useBusybox = applets.contains("sh");
				if (!useBusybox) break;
				useBusybox = applets.contains("chmod");
				if (!useBusybox) break;
				useBusybox = applets.contains("echo");
				if (!useBusybox) break;
				useBusybox = applets.contains("cat");
				if (!useBusybox) break;
				useBusybox = applets.contains("sysctl");
			} while (false);

			String rootCommand;
			if (useBusybox) rootCommand = "su -c 'busybox sh'";
			else rootCommand = "su";
			root = Runtime.getRuntime().exec(rootCommand);
			exec = new PrintStream(root.getOutputStream());
			// stderr = root.getErrorStream();
		}
	}

	void releaseRoot() {
		if (root != null) {
			exec.println("exit");
			exec.flush();
			exec.close();
			try {
				root.waitFor();
			} catch (InterruptedException ignore) {
			}
			exec = null;
			root = null;
		}
	}

	/*void logSu() throws IOException {
		exec.println("exit");
		exec.flush();
		try {
			root.waitFor();
		} catch (InterruptedException ignored) {
		}
		Log.d("ROOT", "Log SU:");
		int len = stdout.available();
		byte[] b;
		if (len > 0) {
			b = new byte[len];
			stdout.read(b);
			Log.d("ROOT", new String(b));
		}
		len = stderr.available();
		if (len > 0) {
			b = new byte[len];
			stderr.read(b);
			Log.wtf("ROOT", new String(b));
		}
		exec.close();
		stdout.close();
		stderr.close();
		root = Runtime.getRuntime().exec("su");
		exec = new PrintStream(root.getOutputStream());
		stdout = root.getInputStream();
		stderr = root.getErrorStream();
	}*/

	int getSocRawID() {
		return raw_id;
	}

	private Kernel() throws IOException {
		raw_id = -1;
		final String raw_id_nodes[] = {"/sys/devices/system/soc/soc0/raw_id", "/sys/devices/soc0/raw_id"};
		for (String node : raw_id_nodes) {
			try {
				if (!hasNode(node)) continue;
				grantRead(node);
				raw_id = Integer.parseInt(readNode(node));
				break;
			} catch (Throwable ignore) {
			}
		}
		try {
			if (raw_id == 2375) { // Xiaomi Mi 5
				batteryTemp = new AdaptiveTempReader(this, getThermalZone(22));
			} else {
				batteryTemp = new AdaptiveTempReader(this, "/sys/class/power_supply/battery/temp");
			}
		} catch (Throwable ignore) {
		}

		if (raw_id == 1972 || raw_id == 1973 || raw_id == 1974) { // Xiaomi Mi 3/4/Note
			try {
				socTemp = new AdaptiveTempReader(this, getThermalZone(0));
			} catch (Throwable ignore) {
			}
			try {
				gpuTemp = new AdaptiveTempReader(this, getThermalZone(10));
			} catch (Throwable ignore) {
			}
		} else if (raw_id == 1812) { // Xiaomi Mi 2/2S
			try {
				socTemp = new AdaptiveTempReader(this, getThermalZone(0));
			} catch (Throwable ignore) {
			}
			try {
				gpuTemp = new AdaptiveTempReader(this, getThermalZone(2));
			} catch (Throwable ignore) {
			}
		} else if (raw_id == 94) { // ONEPLUS A5000
			try {
				socTemp = new AdaptiveTempReader(this, getThermalZone(1));
			} catch (Throwable ignore) {
			}
			try {
				gpuTemp = new AdaptiveTempReader(this, getThermalZone(20));
			} catch (Throwable ignore) {
			}
		} else if (raw_id == 95) { // ONEPLUS A3010
			try {
				socTemp = new AdaptiveTempReader(this, getThermalZone(22));
			} catch (Throwable ignore) {
			}
			try {
				gpuTemp = new AdaptiveTempReader(this, getThermalZone(10));
			} catch (Throwable ignore) {
			}
		} else if (raw_id == 2375) { // Xiaomi Mi 5
			try {
				socTemp = new AdaptiveTempReader(this, getThermalZone(1));
			} catch (Throwable ignore) {
			}
			try {
				gpuTemp = new AdaptiveTempReader(this, getThermalZone(16));
			} catch (Throwable ignore) {
			}
		}

		// 获取多簇处理器的配置路径
		/*if (hasNode(cpufreqPath + "/policy0")) {
			policyMap = new SparseArray<>();
			clusterMap = new SparseIntArray();
			try {
				File cpufreq = new File(cpufreqPath);
				String[] files = cpufreq.list(new FilenameFilter() {
					@Override
					public boolean accept(File dir, String name) {
						return name.startsWith("policy");
					}
				});
				for (String f : files) {
					String path = cpufreqPath + "/" + f;
					Scanner sc = new Scanner(new File(path + "/affected_cpus"));
					int firstId = -1, cpuCount = 0;
					while (sc.hasNextInt()) {
						int cpuId = sc.nextInt();
						if (firstId == -1) firstId = cpuId;
						clusterMap.put(cpuId, cluster);
						policyMap.put(cpuId, path);
						cpuCount++;
					}
					clusterPolicies.add(new ClusterPolicy(firstId, cpuCount, path));
					cluster++;
				}
			} catch (Throwable e) {
				Log.w("Dynatweak", "cluster policy detect error", e);
				clusterMap = null;
			}
		}*/

		clusterPolicies = new ArrayList<>();
		final String cpuPath = "/sys/devices/system/cpu";
		int cpuId = 0;
		cpuCores = new ArrayList<>();
		while (new File(cpuPath + "/cpu" + cpuId).exists()) {
			try {
				CpuCore cpu;
				if (raw_id == 1972 || raw_id == 1973 || raw_id == 1974) { // Xiaomi Mi 3/4/Note
					cpu = new CpuCore(cpuId, "/sys/devices/system/cpu",
							getThermalZone(cpuId + 5));
				} else if (raw_id == 94) { // ONEPLUS A5000
					cpu = new CpuCore(cpuId, "/sys/devices/system/cpu",
							getThermalZone(cpuId + 11));
				} else if (raw_id == 95) { // ONEPLUS A3010
					cpu = new CpuCore(cpuId, "/sys/devices/system/cpu",
							getThermalZone(cpuId + 5));
				} else if (raw_id == 2375) { // Xiaomi Mi 5
					cpu = new CpuCore(cpuId, "/sys/devices/system/cpu",
							getThermalZone(cpuId + 9));
				} else if (raw_id == 1812) { // Xiaomi Mi 2/2S
					cpu = new CpuCore(cpuId, "/sys/devices/system/cpu",
							getThermalZone(cpuId + 7));
				} else {
					cpu = new CpuCore(cpuId, "/sys/devices/system/cpu",
							null);
				}
				cpuCores.add(cpu);
			} catch (Throwable ignore) {
			}
			cpuId++;
		}

		int cluster = 0;
		for (CpuCore cpu : cpuCores) {
			boolean exist = false;
			for (ClusterPolicy clusterPolicy : clusterPolicies) {
				for (int affectedCpu : clusterPolicy.AffectedCpu) {
					if (cpu.getId() == affectedCpu) {
						exist = true;
						break;
					}
				}
				if (exist) break;
			}
			if (exist) continue;
			try {
				String policy = cpu.getPath() + "/cpufreq";
				String[] raw = readNode(policy + "/affected_cpus").split(" ");
				int[] affected_cpus = new int[raw.length];
				int i = 0;
				for (String raw_id : raw) {
					int id = Integer.parseInt(raw_id);
					cpuCores.get(id).setCluster(cluster);
					affected_cpus[i++] = id;
				}
				clusterPolicies.add(new ClusterPolicy(cpu.getId(), affected_cpus, policy));
				cluster++;
			} catch (Throwable ignore) {
			}
		}
	}

	class ClusterPolicy {
		int getStartCpu() {
			return StartCpu;
		}

		String getPolicyPath() {
			return PolicyPath;
		}

		int getCpuCount() {
			return AffectedCpu.length;
		}

		private int StartCpu;
		private int[] AffectedCpu;
		private String PolicyPath;

		ClusterPolicy(int startCpu, int[] cpu, String path) {
			StartCpu = startCpu;
			AffectedCpu = cpu;
			PolicyPath = path;
		}
	}

	class CpuCore {
		private List<Integer> scaling_available_frequencies = null;
		private int id, cluster;
		private String path;
		AdaptiveTempReader tempNode = null;

		CpuCore(int id, String path, String tempNode) {
			if (tempNode != null) {
				grantRead(tempNode);
				try {
					this.tempNode = new AdaptiveTempReader(Kernel.this, tempNode);
				} catch (Throwable ignore) {
				}
			}
			this.path = path + "/cpu" + id;
			this.id = id;
			grantAllPermissions();
		}

		void grantAllPermissions() {
			changeMode(this.path + "/online", "0644");
			changeMode(this.path + "/cpufreq/scaling_max_freq", "0644");
			changeMode(this.path + "/cpufreq/scaling_min_freq", "0644");
			changeMode(this.path + "/cpufreq/scaling_governor", "0644");
			grantRead(this.path + "/cpufreq/scaling_available_frequencies");
			grantRead(this.path + "/cpufreq/scaling_available_governors");
			grantRead(this.path + "/cpufreq/scaling_cur_freq");
			grantRead(this.path + "/cpufreq/cpuinfo_max_freq");
			grantRead(this.path + "/cpufreq/cpuinfo_min_freq");
			grantRead(this.path + "/cpufreq/cpuinfo_cur_freq");
		}

		int getId() {
			return id;
		}

		int getCluster() {
			return cluster;
		}

		void setCluster(int newCluster) {
			cluster = newCluster;
		}

		String getPath() {
			return path;
		}

		List<Integer> getScalingAvailableFrequencies() throws IOException {
			String frequencies = readNode(path + "/cpufreq/scaling_available_frequencies");
			List<Integer> scaling_available_frequencies = new ArrayList<>();
			for (String s : frequencies.trim().split(" ")) {
				scaling_available_frequencies.add(Integer.parseInt(s));
			}
			if (scaling_available_frequencies.size() < 1) scaling_available_frequencies = null;
			return scaling_available_frequencies;
		}

		List<String> getScalingAvailableGovernors() throws IOException {
			String governors = readNode(path + "/cpufreq/scaling_available_governors");
			return Arrays.asList(governors.trim().split(" "));
		}


		int fitFrequency(int frequency) throws IOException {
			if (scaling_available_frequencies == null) {
				scaling_available_frequencies = getScalingAvailableFrequencies();
			}
			for (Integer scaling_available_frequency : scaling_available_frequencies)
				if (scaling_available_frequency >= frequency) return scaling_available_frequency;
			return scaling_available_frequencies.get(scaling_available_frequencies.size() - 1);
		}

		int fitPercentage(double percentage) throws IOException {
			if (scaling_available_frequencies == null) {
				scaling_available_frequencies = getScalingAvailableFrequencies();
			}
			return fitFrequency((int) (scaling_available_frequencies.get(scaling_available_frequencies.size() - 1)
					* percentage));
		}


		/*int getScalingMaxFrequency() throws IOException {
			String ret = readNode(path + "/cpufreq/scaling_max_freq");
			return Integer.parseInt(ret);
		}*/

		void setScalingMaxFrequency(int frequency, boolean locked) throws IOException {
			setNode(path + "/cpufreq/scaling_max_freq", frequency + "", locked);
		}

		/*int getScalingMinFrequency() throws IOException {
			String ret = readNode(path + "/cpufreq/scaling_min_freq");
			return Integer.parseInt(ret);
		}*/

		void setScalingMinFrequency(int frequency, boolean locked) throws IOException {
			setNode(path + "/cpufreq/scaling_min_freq", frequency + "", locked);
		}

		int getScalingCurrentFrequency() throws IOException {
			String ret = readNode(path + "/cpufreq/scaling_cur_freq");
			return Integer.parseInt(ret);
		}

		int getMaxFrequency() throws IOException {
			String ret = readNode(path + "/cpufreq/cpuinfo_max_freq");
			return Integer.parseInt(ret);
		}

		int getMinFrequency() throws IOException {
			String ret = readNode(path + "/cpufreq/cpuinfo_min_freq");
			return Integer.parseInt(ret);
		}

		/*int getCurrentFrequency() throws IOException {
			String ret = readNode(path + "/cpufreq/cpuinfo_cur_freq");
			return Integer.parseInt(ret);
		}*/

		void setGovernor(String governor, boolean locked) throws IOException {
			setNode(path + "/cpufreq/scaling_governor", governor, locked);
		}

		boolean isOnline() {
			boolean ret;
			try {
				ret = Integer.parseInt(readNode(path + "/online")) != 0;
			} catch (Exception e) {
				Log.w("Dynatweak", "isOnline failed", e);
				ret = true;
			}
			return ret;
		}

		/*void setOnline(boolean online) {
			if (online) {
				for (int putOnline = 0; putOnline < 10; putOnline++) {
					setNode(path + "/online", "1");
					if (!isOnline()) {
						try {
							Thread.sleep(5);
						} catch (InterruptedException e) {
							throw new RuntimeException(e);
						}
					} else break;
				}
			} else
				setNode(path + "/online", "0");
		}*/

		void setOnline(boolean online, boolean locked) throws IOException {
			if (online)
				for (int putOnline = 0; putOnline < 10; putOnline++) {
					setNode(path + "/online", "1", locked);
					if (!isOnline()) {
						try {
							Thread.sleep(5);
						} catch (InterruptedException ignore) {
						}
					} else break;
				}
			else
				setNode(path + "/online", "0", locked);
		}

		String getGovernor() throws IOException {
			return readNode(path + "/cpufreq/scaling_governor");
		}

		/*String getRunQueueAverage() throws IOException {
			return readNode(path + "/rq-stat/run_queue_avg");
		}*/

		boolean hasTemperature() {
			return tempNode != null;
		}

		double getTemperature() throws IOException {
			return tempNode.read();
		}
	}
}

class AdaptiveTempReader {
	private double divider;
	private RandomAccessFile fr;

	AdaptiveTempReader(Kernel k, String node) throws FileNotFoundException {
		try {
			k.grantRead(node);
			fr = new RandomAccessFile(node, "r");
			divider = 1.0;
			read();
		} catch (Throwable e) {
			Log.w("Dynatweak", "AdaptiveTempReader node=" + node, e);
			throw new FileNotFoundException();
		}
	}

	/*AdaptiveTempReader(String node) throws FileNotFoundException {
		try {
			fr = new RandomAccessFile(node, "r");
			divider = 1.0;
			read();
		} catch (Throwable e) {
			Log.w("Dynatweak", "AdaptiveTempReader node=" + node, e);
			throw new FileNotFoundException();
		}
	}*/

	double read() throws IOException {
		fr.seek(0);
		int raw = Integer.parseInt(fr.readLine());
		double value = raw / divider;
		while (Math.abs(value) >= 150.0) {
			divider *= 10.0;
			value = raw / divider;
		}
		return value;
	}
}

class MaxTempReader extends AdaptiveTempReader {

	private double max = 0;

	MaxTempReader(Kernel k, String node) throws FileNotFoundException {
		super(k, node);
	}

	double read() throws IOException {
		double value = super.read();
		if (value > max) max = value;
		return max;
	}
}