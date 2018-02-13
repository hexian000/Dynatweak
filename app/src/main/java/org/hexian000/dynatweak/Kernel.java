package org.hexian000.dynatweak;

import android.util.Log;
import eu.chainfire.libsuperuser.Shell;

import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Created by hexian on 2017/6/18.
 * Kernel interface
 */
class Kernel {

	static final String LOG_TAG = "Dynatweak";

	private static Kernel instance = null;
	final List<CpuCore> cpuCores;
	private final List<ClusterPolicy> clusterPolicies;
	private List<String> commands;
	private int raw_id;
	private AdaptiveTempReader socTemp = null, batteryTemp = null, gpuTemp = null;

	private Kernel() {
		commands = new ArrayList<>();
		raw_id = -1;
		final String raw_id_nodes[] = {"/sys/devices/system/soc/soc0/raw_id", "/sys/devices/soc0/raw_id"};
		for (String node : raw_id_nodes) {
			try {
				if (!hasNode(node)) continue;
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

		clusterPolicies = new ArrayList<>();
		final String cpuPath = "/sys/devices/system/cpu";
		int cpuId = 0;
		cpuCores = new ArrayList<>();
		while (new File(cpuPath + "/cpu" + cpuId).exists()) {
			try {
				CpuCore cpu;
				switch (raw_id) {
					case 1972:
					case 1973:
					case 1974:  // Xiaomi Mi 3/4/Note
						cpu = new CpuCore(cpuId, "/sys/devices/system/cpu",
								getThermalZone(cpuId + 5));
						break;
					case 70:  // Xiaomi Mi 5X
						cpu = new CpuCore(cpuId, "/sys/devices/system/cpu",
								getThermalZone(cpuId + 10));
						break;
					case 94:  // ONEPLUS A5000
						cpu = new CpuCore(cpuId, "/sys/devices/system/cpu",
								getThermalZone(cpuId + 11));
						break;
					case 95:  // ONEPLUS A3010
						cpu = new CpuCore(cpuId, "/sys/devices/system/cpu",
								getThermalZone(cpuId + 5));
						break;
					case 2375:  // Xiaomi Mi 5
						cpu = new CpuCore(cpuId, "/sys/devices/system/cpu",
								getThermalZone(cpuId + 9));
						break;
					case 1812:  // Xiaomi Mi 2/2S
						cpu = new CpuCore(cpuId, "/sys/devices/system/cpu",
								getThermalZone(cpuId + 7));
						break;
					default:
						cpu = new CpuCore(cpuId, "/sys/devices/system/cpu",
								null);
						break;
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
				String policy = "/sys/devices/system/cpu/cpufreq/policy" + cpu.getId();
				if (!hasNode(policy)) policy = null;
				String[] raw = readNodeByRoot(policy + "/affected_cpus").split(" +");
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

	static Kernel getInstance() {
		if (instance == null) instance = new Kernel();
		return instance;
	}

	boolean hasNode(String path) {
		return new File(path).exists();
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

	List<ClusterPolicy> getAllPolicies() {
		return clusterPolicies;
	}

	boolean hasCoreControl() {
		return hasNode("/sys/module/msm_thermal/core_control/enabled") ||
				hasNode("/sys/module/msm_thermal/core_control/cpus_offlined");
	}

	void setCoreControlMask(int mask) {
		if (mask > 0) {
			setNode("/sys/module/msm_thermal/core_control/enabled", "1", true);
			setNode("/sys/module/msm_thermal/core_control/cpus_offlined", mask + "", true);
		} else {
			setNode("/sys/module/msm_thermal/core_control/enabled", "0", true);
		}
	}

	String readNode(String path) throws IOException {
		return new BufferedReader(new FileReader(path)).readLine();
	}

	private String readNodeByRoot(String path) {
		List<String> result = Shell.SU.run("cat '" + path + "'");
		if (result != null && result.size() > 0) {
			return result.get(0);
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
		commands = new ArrayList<>();
	}

	void setNode(String path, String value) {
		setNode(path, value, false);
	}

	private void setNode(String path, String value, boolean lock) {
		if (lock) {
			commands.add("[ -f '" + path + "' ] && " +
					"chmod +w '" + path + "' && " +
					"( echo '" + value + "'>'" + path + "' ; " +
					"chmod -w '" + path + "' )");
		} else {
			commands.add("[ -f '" + path + "' ] && " +
					"echo '" + value + "'>'" + path + "'");
		}
	}

	boolean trySetNode(String node, String value) {
		if (hasNode(node)) {
			List<String> result = Shell.SU.run("echo '" + value + "'>'" + node + "' ; cat '" + node + "'");
			if (result.size() > 0 && result.get(0).equals(value))
				return true;
			else
				Log.w(LOG_TAG, "trySetNode " + node +
						" got: \"" + result.get(0) +
						"\" expected: \"" + value + "\"");
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

	class ClusterPolicy {
		private final int StartCpu;
		private final int[] AffectedCpu;
		private final String PolicyPath;

		ClusterPolicy(int startCpu, int[] cpu, String path) {
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

		CpuCore(int id, String path, String tempNode) {
			if (tempNode != null) {
				// grantRead(tempNode);
				try {
					this.tempNode = new AdaptiveTempReader(tempNode);
				} catch (Throwable ignore) {
				}
			}
			this.path = path + "/cpu" + id;
			this.id = id;
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
			return fitFrequency((int) (scaling_available_frequencies.
					get(scaling_available_frequencies.size() - 1)
					* percentage));
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

		void setOnline(boolean online, boolean locked) {
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
			Log.w(Kernel.LOG_TAG, "AdaptiveTempReader node=" + node, e);
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
		if (value > max) max = value;
		return max;
	}
}