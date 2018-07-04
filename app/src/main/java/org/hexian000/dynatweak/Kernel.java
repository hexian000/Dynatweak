package org.hexian000.dynatweak;

import android.util.Log;
import eu.chainfire.libsuperuser.Shell;

import java.io.*;
import java.util.*;

import static org.hexian000.dynatweak.Dynatweak.LOG_TAG;

/**
 * Created by hexian on 2017/6/18.
 * Kernel interface
 */
class Kernel {
	private final static boolean hasRoot = Shell.SU.available();
	private final static boolean isSELinux = Shell.SU.isSELinuxEnforcing();
	private static Kernel instance = null;
	final List<CpuCore> cpuCores;
	private final List<FrequencyPolicy> frequencyPolicies;
	private final List<String> commands;
	private int raw_id;
	private int clusterCount;
	private AdaptiveTempReader socTemp = null, batteryTemp = null, gpuTemp = null;

	private Kernel() {
		Log.d(LOG_TAG, "hasRoot:" + hasRoot + " isSELinux:" + isSELinux);
		commands = new ArrayList<>();
		raw_id = -1;
		final String raw_id_nodes[] = {"/sys/devices/system/soc/soc0/raw_id", "/sys/devices/soc0/raw_id"};
		for (String node : raw_id_nodes) {
			try {
				if (!hasNode(node)) {
					continue;
				}
				raw_id = Integer.parseInt(readNode(node));
				break;
			} catch (Throwable ignore) {
			}
		}
		try {
			if (raw_id == 2375) { // Xiaomi Mi 5
				batteryTemp = new AdaptiveTempReader(getThermalZone(22));
			} else {
				batteryTemp = new AdaptiveTempReader("/sys/class/power_supply/battery/temp");
			}
		} catch (Throwable ignore) {
		}

		switch (raw_id) {
		case 1972:
		case 1973:
		case 1974:  // Xiaomi Mi 3/4/Note
			try {
				socTemp = new AdaptiveTempReader(getThermalZone(0));
			} catch (Throwable ignore) {
			}
			try {
				gpuTemp = new AdaptiveTempReader(getThermalZone(10));
			} catch (Throwable ignore) {
			}
			break;
		case 1812:  // Xiaomi Mi 2/2S
			try {
				socTemp = new AdaptiveTempReader(getThermalZone(0));
			} catch (Throwable ignore) {
			}
			try {
				gpuTemp = new AdaptiveTempReader(getThermalZone(2));
			} catch (Throwable ignore) {
			}
			break;
		case 94:  // ONEPLUS A5000
			try {
				socTemp = new AdaptiveTempReader(getThermalZone(1));
			} catch (Throwable ignore) {
			}
			try {
				gpuTemp = new AdaptiveTempReader(getThermalZone(20));
			} catch (Throwable ignore) {
			}
			break;
		case 95:  // ONEPLUS A3010
			try {
				socTemp = new AdaptiveTempReader(getThermalZone(22));
			} catch (Throwable ignore) {
			}
			try {
				gpuTemp = new AdaptiveTempReader(getThermalZone(10));
			} catch (Throwable ignore) {
			}
			break;
		case 2375:  // Xiaomi Mi 5
			try {
				socTemp = new AdaptiveTempReader(getThermalZone(1));
			} catch (Throwable ignore) {
			}
			try {
				gpuTemp = new AdaptiveTempReader(getThermalZone(16));
			} catch (Throwable ignore) {
			}
			break;
		case 70:  // Xiaomi Mi 5X
			try {
				socTemp = new AdaptiveTempReader(getThermalZone(20));
			} catch (Throwable ignore) {
			}
			gpuTemp = null;
			break;
		}

		frequencyPolicies = new ArrayList<>();
		final String cpuPath = "/sys/devices/system/cpu";
		int cpuId = 0, cluster = -1;
		cpuCores = new ArrayList<>();
		Set<Integer> clusterMap = new HashSet<>();
		while (new File(cpuPath + "/cpu" + cpuId).exists()) {
			try {
				CpuCore cpu;
				switch (raw_id) {
				case 1972:
				case 1973:
				case 1974:  // Xiaomi Mi 3/4/Note
					cpu = new CpuCore(cpuId,
							getThermalZone(cpuId + 5));
					break;
				case 70:  // Xiaomi Mi 5X
					cpu = new CpuCore(cpuId,
							getThermalZone(cpuId + 10));
					break;
				case 94:  // ONEPLUS A5000
					cpu = new CpuCore(cpuId,
							getThermalZone(cpuId + 11));
					break;
				case 95:  // ONEPLUS A3010
					cpu = new CpuCore(cpuId,
							getThermalZone(cpuId + 5));
					break;
				case 2375:  // Xiaomi Mi 5
					cpu = new CpuCore(cpuId,
							getThermalZone(cpuId + 9));
					break;
				case 1812:  // Xiaomi Mi 2/2S
					cpu = new CpuCore(cpuId,
							getThermalZone(cpuId + 7));
					break;
				default:
					cpu = new CpuCore(cpuId,
							null);
					break;
				}
				if (clusterMap.add(cpu.getMaxFrequency())) {
					cluster++;
				}
				cpu.setCluster(cluster);
				cpuCores.add(cpu);
			} catch (Throwable ignore) {
			}
			cpuId++;
		}
		clusterCount = cluster + 1;

		int policy = 0;
		for (CpuCore cpu : cpuCores) {
			boolean exist = false;
			for (FrequencyPolicy frequencyPolicy : frequencyPolicies) {
				for (int affectedCpu : frequencyPolicy.AffectedCpu) {
					if (cpu.getId() == affectedCpu) {
						exist = true;
						break;
					}
				}
				if (exist) {
					break;
				}
			}
			if (exist) {
				continue;
			}
			try {
				String policyPath = "/sys/devices/system/cpu/cpufreq/policy" + cpu.getId();
				if (!hasNodeByRoot(policyPath)) {
					policyPath = cpu.path + "/cpufreq";
				}
				String affectedCpus = readNodeByRoot(policyPath + "/related_cpus");
				Log.d(LOG_TAG, "policy: " + policyPath + " related_cpus: " + affectedCpus);
				if (affectedCpus.length() > 0) {
					String[] raw = affectedCpus.split("\\s+");
					int[] affected_cpus = new int[raw.length];
					int i = 0;
					for (String raw_id : raw) {
						int id = Integer.parseInt(raw_id);
						cpuCores.get(id).setPolicy(policy);
						affected_cpus[i++] = id;
					}
					frequencyPolicies.add(new FrequencyPolicy(cpu.getId(), affected_cpus, policyPath));
					policy++;
				}
			} catch (Throwable ex) {
				Log.w(LOG_TAG, "read cpu policy failed", ex);
			}
		}
	}

	static Kernel getInstance() {
		if (instance == null) {
			instance = new Kernel();
		}
		return instance;
	}

	public int getClusterCount() {
		return clusterCount;
	}

	boolean hasNode(String path) {
		return new File(path).exists();
	}

	boolean hasNodeByRoot(String path) {
		if (!hasRoot) {
			return hasNode(path);
		}
		List<String> result = Shell.SU.run("[ -e '" + path + "' ] && echo OK");
		return result != null && result.size() > 0 && "OK".equals(result.get(0));
	}

	String getThermalZone(int id) {
		return "/sys/class/thermal/thermal_zone" + id + "/temp";
	}

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
			String[] schedulers = readNodeByRoot(node).trim().split(" +");
			for (String scheduler : schedulers) {
				if (scheduler.startsWith("[") && scheduler.endsWith("]")) {
					scheduler = scheduler.substring(1, scheduler.length() - 1);
					Log.d(LOG_TAG, "Found current scheduler - \"" + scheduler + "\"");
					ret.add(scheduler);
				} else {
					ret.add(scheduler);
				}
			}
		} catch (Throwable ex) {
			Log.e(LOG_TAG, "listBlockAvailableScheduler", ex);
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

	List<FrequencyPolicy> getAllPolicies() {
		return frequencyPolicies;
	}

	boolean hasCoreControl() {
		return hasNode("/sys/module/msm_thermal/core_control/cpus_offlined");
	}

	void setCoreControlMask(int mask) {
		setNode("/sys/module/msm_thermal/parameters/enabled", "Y");
		setNode("/sys/module/msm_thermal/core_control/enabled", "1");
		setNode("/sys/module/msm_thermal/core_control/cpus_offlined", mask + "");
	}

	String readNode(String path) throws IOException {
		return new BufferedReader(new FileReader(path)).readLine();
	}

	private String readNodeByRoot(String path) {
		if (!hasRoot) {
			try {
				return readNode(path);
			} catch (IOException e) {
				Log.w(LOG_TAG, "readNode got exception - \"" + path + "\"", e);
				return "";
			}
		}
		List<String> result = Shell.SU.run("cat '" + path + "'");
		if (result != null && result.size() > 0) {
			StringBuilder sb = new StringBuilder();
			for (String line : result) {
				sb.append(line).append('\n');
			}
			return sb.toString();
		} else {
			Log.w(LOG_TAG, "readNodeByRoot got nothing - \"" + path + "\"");
			return "";
		}
	}

	void commit() {
		Log.d(LOG_TAG, "Committing " + commands.size() + " lines...");
		List<String> result = Shell.SU.run(commands);
		for (String line : result) {
			Log.d(LOG_TAG, "STDOUT: " + line);
		}
		commands.clear();
	}

	void setNode(String path, String value) {
		commands.add("[ -f '" + path + "' ] && " +
				"echo '" + value + "'>'" + path + "'");
	}

	boolean trySetNode(String node, String value) {
		if (hasNode(node)) {
			List<String> result = Shell.SU.run("echo '" + value + "'>'" + node + "' ; cat '" + node + "'");
			if (result != null && result.size() > 0) {
				if (result.get(0).equals(value)) {
					return true;
				} else {
					Log.w(LOG_TAG, "trySetNode " + node +
							" got: \"" + result.get(0) +
							"\" expected: \"" + value + "\"");
				}
			} else {
				Log.w(LOG_TAG, "trySetNode got nothing: " + node + " broken root?");
			}
		} else {
			Log.w(LOG_TAG, "trySetNode not found: " + node);
		}
		return false;
	}

	void setSysctl(String node, String value) {
		String path = "/proc/sys/" + node.replace('.', '/');
		commands.add("( [ -f '" + path + "' ] && echo '" + value + "' > " + path + " ) || " +
				"( [ ! -z `which sysctl` ] && sysctl -w " + node + "=" + value + " )");
	}

	void runAsRoot(String command) {
		// Log.d(LOG_TAG, command);
		Shell.SU.run(command);
	}

	int getSocRawID() {
		return raw_id;
	}

	class FrequencyPolicy {
		private final int StartCpu;
		private final int[] AffectedCpu;
		private final String PolicyPath;

		FrequencyPolicy(int startCpu, int[] cpu, String path) {
			StartCpu = startCpu;
			AffectedCpu = cpu;
			PolicyPath = path;
		}

		int getStartCpu() {
			return StartCpu;
		}

		String getPolicyPath() {
			return PolicyPath;
		}

		int getCpuCount() {
			return AffectedCpu.length;
		}
	}

	class CpuCore {
		private final int id;
		private final String path;
		AdaptiveTempReader tempNode = null;
		private List<Integer> scaling_available_frequencies = null;
		private int cluster;
		private int policy;

		CpuCore(int id, String tempNode) {
			if (tempNode != null) {
				// grantRead(tempNode);
				try {
					this.tempNode = new AdaptiveTempReader(tempNode);
				} catch (Throwable ignore) {
				}
			}
			this.path = "/sys/devices/system/cpu/cpu" + id;
			this.id = id;
		}

		public int getPolicy() {
			return policy;
		}

		void setPolicy(int policy) {
			this.policy = policy;
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
			if (scaling_available_frequencies.size() < 1) {
				scaling_available_frequencies = null;
			}
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
			for (Integer scaling_available_frequency : scaling_available_frequencies) {
				if (scaling_available_frequency >= frequency) {
					return scaling_available_frequency;
				}
			}
			return scaling_available_frequencies.get(scaling_available_frequencies.size() - 1);
		}

		int fitPercentage(double percentage) throws IOException {
			if (scaling_available_frequencies == null) {
				scaling_available_frequencies = getScalingAvailableFrequencies();
			}
			return fitFrequency(
					(int) (scaling_available_frequencies.get(scaling_available_frequencies.size() - 1) * percentage));
		}

		void setScalingMaxFrequency(int frequency) {
			setNode(path + "/cpufreq/scaling_max_freq", frequency + "");
		}

		void setScalingMinFrequency(int frequency) {
			setNode(path + "/cpufreq/scaling_min_freq", frequency + "");
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

		void trySetGovernor(String governor) {
			trySetNode(path + "/cpufreq/scaling_governor", governor);
		}

		boolean isOnline() {
			boolean ret;
			try {
				ret = Integer.parseInt(readNode(path + "/online")) != 0;
			} catch (Exception e) {
				Log.w(LOG_TAG, "isOnline failed", e);
				ret = true;
			}
			return ret;
		}

		void setOnline(boolean online) {
			if (online) {
				for (int putOnline = 0; putOnline < 10; putOnline++) {
					setNode(path + "/online", "1");
					if (!isOnline()) {
						try {
							Thread.sleep(5);
						} catch (InterruptedException ignore) {
						}
					} else {
						break;
					}
				}
			} else {
				setNode(path + "/online", "0");
			}
		}

		boolean trySetOnline(boolean online) {
			String value = "0";
			if (online) {
				value = "1";
			}
			for (int putOnline = 0; putOnline < 10; putOnline++) {
				if (!trySetNode(path + "/online", value)) {
					try {
						Thread.sleep(50);
					} catch (InterruptedException e) {
						return false;
					}
				} else {
					return true;
				}
			}
			return false;
		}

		String getGovernor() throws IOException {
			return readNode(path + "/cpufreq/scaling_governor");
		}

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

class MaxTempReader extends AdaptiveTempReader {

	private double max = 0;

	MaxTempReader(String node) throws FileNotFoundException {
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