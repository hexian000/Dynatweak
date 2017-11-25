package org.hexian000.dynatweak;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by hexian on 2017/6/18.
 * Boot time tweaks
 */
public class BootReceiver extends BroadcastReceiver {

	static final int PROFILE_BANLANCED = 2;
	private static final int PROFILE_DISABLED = 0;
	private static final int PROFILE_POWERSAVE = 1;
	private static final int PROFILE_PERFORMANCE = 3;
	private static final int PROFILE_GAMING = 4;
	private static final int HOTPLUG_ALLCORES = 0;
	private static final int HOTPLUG_LITTLECORES = 1;
	private static final int HOTPLUG_DRIVER = 2;

	static void tweak(int hotplug, int profile) throws IOException {
		Kernel k = Kernel.getInstance();
		Kernel.CpuCore cpu0 = k.cpuCores.get(0);

		// CPU Hotplug
		k.runAsRoot("stop mpdecision");
		try {
			Thread.sleep(1000);
		} catch (InterruptedException ignore) {
		}
		k.trySetNode("/proc/hps/enabled", "0");
		k.trySetNode("/sys/module/blu_plug/parameters/enabled", "0");
		k.trySetNode("/sys/module/autosmp/parameters/enabled", "N");
		k.trySetNode("/sys/kernel/alucard_hotplug/hotplug_enable", "0");
		k.trySetNode("/sys/kernel/msm_mpdecision/conf/enabled", "0");
		k.trySetNode("/sys/module/msm_hotplug/msm_enabled", "0");

		// Entropy
		k.setSysctl("kernel.random.read_wakeup_threshold", "128");
		k.setSysctl("kernel.random.write_wakeup_threshold", "512");

		// VM
		k.setSysctl("vm.dirty_expire_centisecs", "2000");
		k.setSysctl("vm.dirty_writeback_centisecs", "1000");

		// Misc
		k.trySetNode("/sys/kernel/fast_charge/force_fast_charge", "1");
		k.trySetNode("/sys/kernel/sched/arch_power", "1");
		k.trySetNode("/sys/module/workqueue/parameters/power_efficent", "Y");

		// Thermal
		k.trySetNode("/sys/module/msm_thermal/parameters/enabled", "N");
		if (k.hasCoreControl())
			k.setCoreControlMask(0);

		// IO
		List<String> block = k.listBlockDevices();
		for (String i : block) {
			Log.i(Kernel.LOG_TAG, "block device: " + i + " detected");
			if (k.hasNode(i + "/queue/iostats") &&
					k.hasNode(i + "/queue/add_random") &&
					k.hasNode(i + "/queue/read_ahead_kb") &&
					k.hasNode(i + "/queue/rq_affinity") &&
					k.hasNode(i + "/queue/scheduler") &&
					k.hasNode(i + "/queue/rotational")) {
				k.trySetNode(i + "/queue/iostats", "0");
				k.trySetNode(i + "/queue/add_random", "0");
				k.trySetNode(i + "/queue/read_ahead_kb", "512");
				k.trySetNode(i + "/queue/rq_affinity", "1");
				k.trySetNode(i + "/queue/rotational", "0");
				List<String> schedulers = k.listBlockAvailableScheduler(i + "/queue/scheduler");
				String scheduler = null;
				if (schedulers.contains("maple"))
					scheduler = "maple";
				else if (schedulers.contains("fiops"))
					scheduler = "fiops";
				else if (schedulers.contains("sioplus"))
					scheduler = "sioplus";
				else if (schedulers.contains("sio"))
					scheduler = "sio";
				else if (schedulers.contains("zen"))
					scheduler = "zen";
				else if (schedulers.contains("bfq"))
					scheduler = "bfq";
				else if (schedulers.contains("deadline"))
					scheduler = "deadline";
				else if (schedulers.contains("cfq"))
					scheduler = "cfq";
				else if (schedulers.contains("noop"))
					scheduler = "noop";
				if (scheduler != null) {
					k.trySetNode(i + "/queue/scheduler", scheduler);
				}
			}
		}

		List<Kernel.ClusterPolicy> allPolicy = k.getAllPolicies();
		List<String> allGovernors = cpu0.getScalingAvailableGovernors();
		List<String> governor = new ArrayList<>();
		List<Integer> profiles = new ArrayList<>();
		final boolean multiCluster = allPolicy.size() > 1;

		if (!multiCluster) { // 单簇处理器
			Log.d(Kernel.LOG_TAG, "single-cluster profile=" + profile);
			switch (profile) {
				case PROFILE_DISABLED:
					break;
				case PROFILE_POWERSAVE: {
					final String[] preferList = {"zzmoove"};
					governor.add(preferGovernor(allGovernors, preferList));
					break;
				}
				case PROFILE_BANLANCED: {
					final String[] preferList = {"blu_active", "zzmoove"};
					governor.add(preferGovernor(allGovernors, preferList));
					break;
				}
				case PROFILE_PERFORMANCE: {
					final String[] preferList = {"ondemand", "blu_active", "zzmoove"};
					governor.add(preferGovernor(allGovernors, preferList));
					break;
				}
				case PROFILE_GAMING: {
					final String[] preferList = {"performance", "ondemand", "blu_active", "zzmoove"};
					governor.add(preferGovernor(allGovernors, preferList));
					break;
				}
			}
			profiles.add(profile);
		} else { // 多簇处理器
			Log.d(Kernel.LOG_TAG, "multi-cluster profile=" + profile);
			for (int i = 0; i < allPolicy.size(); i++) {
				switch (profile) {
					case PROFILE_DISABLED:
						break;
					case PROFILE_POWERSAVE: {
						final String[] preferList = {"zzmoove"};
						governor.add(preferGovernor(allGovernors, preferList));
						profiles.add(PROFILE_POWERSAVE);
						break;
					}
					case PROFILE_BANLANCED: {
						final String[] preferList = {"blu_active", "zzmoove"};
						governor.add(preferGovernor(allGovernors, preferList));
						if (i == 0) {
							profiles.add(PROFILE_BANLANCED);
						} else {
							profiles.add(PROFILE_POWERSAVE);
						}
						break;
					}
					case PROFILE_PERFORMANCE: {
						if (i == 0) {
							final String[] preferList = {"ondemand", "blu_active", "zzmoove"};
							governor.add(preferGovernor(allGovernors, preferList));
							profiles.add(PROFILE_PERFORMANCE);
						} else {
							final String[] preferList = {"blu_active", "zzmoove"};
							governor.add(preferGovernor(allGovernors, preferList));
							profiles.add(PROFILE_BANLANCED);
						}
						break;
					}
					case PROFILE_GAMING: {
						final String[] preferList = {"performance", "ondemand", "blu_active", "zzmoove"};
						governor.add(preferGovernor(allGovernors, preferList));
						profiles.add(PROFILE_GAMING);
						break;
					}
				}
			}
		}

		// CPU Frequency
		for (Kernel.CpuCore cpu : k.cpuCores) {
			for (int trial = 0; trial < 3; trial++) {
				try {
					cpu.setOnline(true, false);
					cpu.setScalingMinFrequency(cpu.getMinFrequency());
					cpu.setScalingMaxFrequency(cpu.getMaxFrequency(), false);
					// Per cpu governor tweak
					if (profile != PROFILE_DISABLED) {
						cpu.trySetGovernor(governor.get(cpu.getCluster()));
						Log.d(Kernel.LOG_TAG, "tweaking cpu " + cpu.getId() +
								": governor=" + governor.get(cpu.getCluster()) +
								", profile=" + profiles.get(cpu.getCluster()) +
								", path=" + cpu.getPath() + "/cpufreq");
						tweakGovernor(k, cpu, cpu.getPath() + "/cpufreq",
								governor.get(cpu.getCluster()), profiles.get(cpu.getCluster()));
					}
					cpu.grantRequiredPermissions();
					break;
				} catch (Throwable ignore) {
				}
			}
		}

		// Per cluster governor tweak
		for (int i = 0; i < allPolicy.size(); i++) {
			Kernel.ClusterPolicy clusterPolicy = allPolicy.get(i);
			if (profile != PROFILE_DISABLED)
				Log.d(Kernel.LOG_TAG, "tweaking cluster " + i +
						": governor=" + governor.get(i) + ", profile=" + profiles.get(i));
			Kernel.CpuCore cpu = k.cpuCores.get(clusterPolicy.getStartCpu());
			// Qualcomm core control
			if (k.hasNode(cpu.getPath() + "/core_ctl")) {
				Log.i(Kernel.LOG_TAG, "policy" + i + ": core_ctl detected");
				k.trySetNode(cpu.getPath() + "/core_ctl/max_cpus", clusterPolicy.getCpuCount() + "");
				k.trySetNode(cpu.getPath() + "/core_ctl/offline_delay_ms", "100");
				if (!k.readNode(cpu.getPath() + "/core_ctl/is_big_cluster").equals("0")) {
					switch (profile) { // big cluster
						case PROFILE_POWERSAVE:
							k.trySetNode(cpu.getPath() + "/core_ctl/busy_down_thres",
									setAllCoresTheSame("40", clusterPolicy.getCpuCount()));
							k.trySetNode(cpu.getPath() + "/core_ctl/busy_up_thres",
									setAllCoresTheSame("90", clusterPolicy.getCpuCount()));
							k.trySetNode(cpu.getPath() + "/core_ctl/min_cpus", "0");
							break;
						case PROFILE_BANLANCED:
							k.trySetNode(cpu.getPath() + "/core_ctl/busy_down_thres",
									setAllCoresTheSame("40", clusterPolicy.getCpuCount()));
							k.trySetNode(cpu.getPath() + "/core_ctl/busy_up_thres",
									setAllCoresTheSame("60", clusterPolicy.getCpuCount()));
							k.trySetNode(cpu.getPath() + "/core_ctl/min_cpus", "0");
							break;
						case PROFILE_PERFORMANCE:
							k.trySetNode(cpu.getPath() + "/core_ctl/busy_down_thres",
									setAllCoresTheSame("30", clusterPolicy.getCpuCount()));
							k.trySetNode(cpu.getPath() + "/core_ctl/busy_up_thres",
									setAllCoresTheSame("60", clusterPolicy.getCpuCount()));
							k.trySetNode(cpu.getPath() + "/core_ctl/min_cpus", Math.min(clusterPolicy.getCpuCount(), 2) + "");
							break;
						case PROFILE_GAMING:
							k.trySetNode(cpu.getPath() + "/core_ctl/busy_down_thres",
									setAllCoresTheSame("0", clusterPolicy.getCpuCount()));
							k.trySetNode(cpu.getPath() + "/core_ctl/busy_up_thres",
									setAllCoresTheSame("0", clusterPolicy.getCpuCount()));
							k.trySetNode(cpu.getPath() + "/core_ctl/min_cpus", clusterPolicy.getCpuCount() + "");
							break;
					}
				} else { // little cluster
					switch (profile) {
						case PROFILE_DISABLED:
							break;
						case PROFILE_POWERSAVE:
							k.trySetNode(cpu.getPath() + "/core_ctl/busy_down_thres",
									setAllCoresTheSame("10", clusterPolicy.getCpuCount()));
							k.trySetNode(cpu.getPath() + "/core_ctl/busy_up_thres",
									setAllCoresTheSame("30", clusterPolicy.getCpuCount()));
							k.trySetNode(cpu.getPath() + "/core_ctl/min_cpus", Math.min(clusterPolicy.getCpuCount(), 2) + "");
							break;
						case PROFILE_BANLANCED:
						case PROFILE_PERFORMANCE:
						case PROFILE_GAMING:
							k.trySetNode(cpu.getPath() + "/core_ctl/busy_down_thres",
									setAllCoresTheSame("0", clusterPolicy.getCpuCount()));
							k.trySetNode(cpu.getPath() + "/core_ctl/busy_up_thres",
									setAllCoresTheSame("0", clusterPolicy.getCpuCount()));
							k.trySetNode(cpu.getPath() + "/core_ctl/min_cpus", clusterPolicy.getCpuCount() + "");
							break;
					}
				}
			}
			// Per policy
			if (profile != PROFILE_DISABLED) {
				String policy = clusterPolicy.getPolicyPath();
				if (policy != null)
					tweakGovernor(k, cpu, policy, governor.get(i), profiles.get(i));
			}
		}

		// CPU big.LITTLE
		switch (profile) {
			case PROFILE_DISABLED:
				break;
			case PROFILE_POWERSAVE:
				k.setSysctl("kernel.sched_downmigrate", "50");
				k.setSysctl("kernel.sched_upmigrate", "99");
				k.setSysctl("kernel.sched_spill_nr_run", "1");
				k.setSysctl("kernel.sched_spill_load", "50");
				break;
			case PROFILE_BANLANCED:
				k.setSysctl("kernel.sched_downmigrate", "70");
				k.setSysctl("kernel.sched_upmigrate", "95");
				k.setSysctl("kernel.sched_spill_nr_run", "4");
				k.setSysctl("kernel.sched_spill_load", "95");
				break;
			case PROFILE_PERFORMANCE:
				k.setSysctl("kernel.sched_downmigrate", "85");
				k.setSysctl("kernel.sched_upmigrate", "90");
				k.setSysctl("kernel.sched_spill_nr_run", "4");
				k.setSysctl("kernel.sched_spill_load", "99");
				break;
			case PROFILE_GAMING:
				k.setSysctl("kernel.sched_downmigrate", "0");
				k.setSysctl("kernel.sched_upmigrate", "0");
				k.setSysctl("kernel.sched_spill_nr_run", "4");
				k.setSysctl("kernel.sched_spill_load", "90");
				break;
		}

		// MSM Performance
		if (k.hasNode("/sys/module/msm_performance/parameters")) {
			Log.i(Kernel.LOG_TAG, "msm_performance detected");
			final String msm_performance = "/sys/module/msm_performance/parameters/";
			StringBuilder cpu_max_freq = new StringBuilder();
			StringBuilder cpu_min_freq = new StringBuilder();
			for (Kernel.CpuCore cpu : k.cpuCores) {
				cpu_max_freq.append(cpu.getId()).append(':').append(cpu.getMaxFrequency()).append(' ');
				cpu_min_freq.append(cpu.getId()).append(':').append(cpu.getMinFrequency()).append(' ');
			}
			k.trySetNode(msm_performance + "cpu_max_freq", cpu_max_freq.toString());
			k.trySetNode(msm_performance + "cpu_min_freq", cpu_min_freq.toString());
			k.trySetNode(msm_performance + "touchboost", "0");
			k.trySetNode(msm_performance + "workload_detect", "1");
		}

		// CPU Boost
		k.trySetNode("/sys/module/cpu_boost/parameters/boost_ms", "40");
		k.trySetNode("/sys/module/cpu_boost/parameters/sync_threshold", cpu0.fitPercentage(0.3) + "");
		for (Kernel.CpuCore cpu : k.cpuCores) {
			String boostFreq, boostFreq_s2;
			if (multiCluster) {
				if (cpu.getCluster() == 0 && cpu.getId() < 2) {
					boostFreq = cpu.getId() + ":" + cpu.fitPercentage(0.5);
					boostFreq_s2 = cpu.getId() + ":" + cpu.fitPercentage(0.3);
				} else {
					boostFreq = cpu.getId() + ":0";
					boostFreq_s2 = cpu.getId() + ":0";
				}
			} else {
				if (cpu.getId() < 2) {
					boostFreq = cpu.getId() + ":" + cpu.fitPercentage(0.5);
					boostFreq_s2 = cpu.getId() + ":" + cpu.fitPercentage(0.3);
				} else {
					boostFreq = cpu.getId() + ":0";
					boostFreq_s2 = cpu.getId() + ":0";
				}
			}
			k.trySetNode("/sys/module/cpu_boost/parameters/input_boost_freq", boostFreq);
			k.trySetNode("/sys/module/cpu_boost/parameters/input_boost_freq_s2", boostFreq_s2);
		}
		k.trySetNode("/sys/module/cpu_boost/parameters/input_boost_ms", "80");
		k.trySetNode("/sys/module/cpu_boost/parameters/input_boost_ms_s2", "160");

		// GPU
		final String gpuNodeRoot = "/sys/class/kgsl/kgsl-3d0";
		Integer num_pwrlevels = null;
		if (k.hasNode(gpuNodeRoot + "/num_pwrlevels")) {
			try {
				num_pwrlevels = Integer.parseInt(
						k.readNode(gpuNodeRoot + "/num_pwrlevels"));
			} catch (Throwable ignore) {
			}
		}

		switch (profile) {
			case PROFILE_DISABLED:
				break;
			case PROFILE_POWERSAVE: // maximize battery life
				if (num_pwrlevels != null) {
					try {
						k.trySetNode(gpuNodeRoot + "/max_pwrlevel", (num_pwrlevels - 1) + "");
						k.trySetNode(gpuNodeRoot + "/min_pwrlevel", (num_pwrlevels - 1) + "");
					} catch (Throwable ignore) {
					}
				}
				break;
			case PROFILE_BANLANCED: // governor controlled with idler
				// Adreno Idler
				k.trySetNode("/sys/module/adreno_idler/parameters/adreno_idler_active", "Y");
				k.trySetNode("/sys/module/adreno_idler/parameters/adreno_idler_downdifferential", "40");
				k.trySetNode("/sys/module/adreno_idler/parameters/adreno_idler_idleworkload", "8000");
				k.trySetNode("/sys/module/adreno_idler/parameters/adreno_idler_idlewait", "50");
				if (num_pwrlevels != null) {
					try {
						k.trySetNode(gpuNodeRoot + "/max_pwrlevel", "0");
						k.trySetNode(gpuNodeRoot + "/min_pwrlevel", (num_pwrlevels - 1) + "");
					} catch (Throwable ignore) {
					}
				}
				break;
			case PROFILE_PERFORMANCE: // governor controlled without idler
				k.trySetNode("/sys/module/adreno_idler/parameters/adreno_idler_active", "N");
				if (num_pwrlevels != null) {
					try {
						k.trySetNode(gpuNodeRoot + "/max_pwrlevel", "0");
						k.trySetNode(gpuNodeRoot + "/min_pwrlevel", (num_pwrlevels - 1) + "");
					} catch (Throwable ignore) {
					}
				}
				break;
			case PROFILE_GAMING: // maximize performance
				if (num_pwrlevels != null) {
					try {
						k.trySetNode(gpuNodeRoot + "/max_pwrlevel", "0");
						k.trySetNode(gpuNodeRoot + "/min_pwrlevel", "0");
					} catch (Throwable ignore) {
					}
				}
				break;
		}

		// hotplug
		switch (hotplug) {
			case HOTPLUG_ALLCORES: // all cores
				k.trySetNode("/sys/devices/system/cpu/sched_mc_power_savings", "0");
				break;
			case HOTPLUG_LITTLECORES: // little cluster or dual-core
				if (multiCluster) {
					int mask = 0, count = 0;
					for (Kernel.CpuCore cpu : k.cpuCores) {
						boolean set;
						if (cpu.getCluster() == 0 || count < 2) {
							set = true;
							count++;
						} else {
							mask |= 1 << cpu.getId();
							set = false;
						}
						cpu.setOnline(set, true);
					}
					if (k.hasCoreControl()) {
						k.setCoreControlMask(mask);
					}
				} else {
					for (Kernel.CpuCore cpu : k.cpuCores) {
						cpu.setOnline(cpu.getId() < 2, true);
					}
					if (k.hasCoreControl()) {
						int cpuCount = k.cpuCores.size();
						if (cpuCount < 31) {
							int allMask = (1 << cpuCount) - 1;
							k.setCoreControlMask(allMask ^ 3);
						}
					}
				}
				k.trySetNode("/sys/devices/system/cpu/sched_mc_power_savings", "1");
				break;
			case HOTPLUG_DRIVER: // use hotplug driver
				final String[][] driverNodes = {{"/sys/kernel/alucard_hotplug/hotplug_enable", "1"},
						{"/sys/module/msm_hotplug/msm_enabled", "1"},
						{"/sys/module/blu_plug/parameters/enabled", "1"},
						{"/sys/module/autosmp/parameters/enabled", "Y"},
						{"/sys/kernel/msm_mpdecision/conf/enabled", "1"},
						{"/proc/hps/enabled", "1"}
				};
				boolean success = false;
				for (String[] param : driverNodes) {
					success = k.trySetNode(param[0], param[1]);
					if (success)
						break;
				}
				if (!success)
					k.runAsRoot("start mpdecision");
				k.trySetNode("/sys/devices/system/cpu/sched_mc_power_savings", "2");
				break;
		}

		k.releaseRoot();
	}

	private static String preferGovernor(List<String> allGovernors, String[] names) {
		for (String name : names) {
			if (allGovernors.contains(name))
				return name;
		}
		return "interactive";
	}

	private static String setAllCoresTheSame(String value, int core) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < core; i++) {
			sb.append(value);
			sb.append(' ');
		}
		return sb.toString();
	}

	private static void tweakGovernor(Kernel k, Kernel.CpuCore cpu, String policy, String governor, int profile) throws IOException {
		switch (profile) {
			case PROFILE_POWERSAVE: // powersave
				switch (governor) {
					case "interactive":
						k.trySetNode(policy + "/interactive/hispeed_freq", cpu.fitPercentage(0.5) + "");
						k.trySetNode(policy + "/interactive/sync_freq", cpu.fitPercentage(0) + "");
						k.trySetNode(policy + "/interactive/above_hispeed_delay", "100000");
						k.trySetNode(policy + "/interactive/go_hispeed_load", "99");
						k.trySetNode(policy + "/interactive/target_loads", "90");
						k.trySetNode(policy + "/interactive/io_is_busy", "0");
						k.trySetNode(policy + "/interactive/timer_rate", "40000");
						k.trySetNode(policy + "/interactive/timer_slack", "40000");
						k.trySetNode(policy + "/interactive/min_sample_time", "80000");
						k.trySetNode(policy + "/interactive/boostpulse_duration", "80000");
						k.trySetNode(policy + "/interactive/use_migration_notif", "1");
						k.trySetNode(policy + "/interactive/ignore_hispeed_on_notif", "1");
						k.trySetNode(policy + "/interactive/max_freq_hysteresis", "80000");
						break;
					case "blu_active":
						k.trySetNode(policy + "/blu_active/above_hispeed_delay",
								"20000 " + cpu.fitPercentage(0.5) + ":40000 " +
										cpu.fitPercentage(0.7) + ":20000 " +
										cpu.fitPercentage(0.85) + ":80000 " +
										cpu.fitPercentage(1) + ":100000");
						k.trySetNode(policy + "/blu_active/align_windows", "1");
						k.trySetNode(policy + "/blu_active/fastlane", "0");
						k.trySetNode(policy + "/blu_active/fastlane_threshold", "80");
						k.trySetNode(policy + "/blu_active/go_hispeed_load", "90");
						k.trySetNode(policy + "/blu_active/hispeed_freq", cpu.fitPercentage(0.5) + "");
						k.trySetNode(policy + "/blu_active/io_is_busy", "0");
						k.trySetNode(policy + "/blu_active/min_sample_time", "20000");
						k.trySetNode(policy + "/blu_active/target_loads",
								"85 " + cpu.fitPercentage(0.6) + ":90 " +
										cpu.fitPercentage(0.8) + ":70 " +
										cpu.fitPercentage(0.9) + ":95");
						k.trySetNode(policy + "/blu_active/timer_rate", "20000");
						k.trySetNode(policy + "/blu_active/timer_slack", "20000");
						break;
					case "zzmoove":
						k.trySetNode(policy + "/zzmoove/auto_adjust_freq_thresholds", "0");
						k.trySetNode(policy + "/zzmoove/disable_hotplug", "1");
						k.trySetNode(policy + "/zzmoove/disable_hotplug_sleep", "1");
						k.trySetNode(policy + "/zzmoove/down_threshold", "40");
						k.trySetNode(policy + "/zzmoove/down_threshold_hotplug1", "45");
						k.trySetNode(policy + "/zzmoove/down_threshold_hotplug2", "55");
						k.trySetNode(policy + "/zzmoove/down_threshold_hotplug3", "65");
						k.trySetNode(policy + "/zzmoove/down_threshold_hotplug4", "55");
						k.trySetNode(policy + "/zzmoove/down_threshold_hotplug5", "55");
						k.trySetNode(policy + "/zzmoove/down_threshold_hotplug6", "55");
						k.trySetNode(policy + "/zzmoove/down_threshold_hotplug7", "55");
						k.trySetNode(policy + "/zzmoove/down_threshold_hotplug_freq1", cpu.fitFrequency(652800) + "");
						k.trySetNode(policy + "/zzmoove/down_threshold_hotplug_freq2", cpu.fitFrequency(960000) + "");
						k.trySetNode(policy + "/zzmoove/down_threshold_hotplug_freq3", cpu.fitFrequency(1267200) + "");
						k.trySetNode(policy + "/zzmoove/down_threshold_hotplug_freq4", "0");
						k.trySetNode(policy + "/zzmoove/down_threshold_hotplug_freq5", "0");
						k.trySetNode(policy + "/zzmoove/down_threshold_hotplug_freq6", "0");
						k.trySetNode(policy + "/zzmoove/down_threshold_hotplug_freq7", "0");
						k.trySetNode(policy + "/zzmoove/down_threshold_sleep", "60");
						k.trySetNode(policy + "/zzmoove/early_demand", "0");
						k.trySetNode(policy + "/zzmoove/early_demand_sleep", "1");
						k.trySetNode(policy + "/zzmoove/fast_scaling_up", "0");
						k.trySetNode(policy + "/zzmoove/fast_scaling_down", "0");
						k.trySetNode(policy + "/zzmoove/fast_scaling_sleep_up", "0");
						k.trySetNode(policy + "/zzmoove/fast_scaling_sleep_down", "0");
						k.trySetNode(policy + "/zzmoove/afs_threshold1", "30");
						k.trySetNode(policy + "/zzmoove/afs_threshold2", "50");
						k.trySetNode(policy + "/zzmoove/afs_threshold3", "70");
						k.trySetNode(policy + "/zzmoove/afs_threshold4", "90");
						k.trySetNode(policy + "/zzmoove/freq_limit", "0");
						k.trySetNode(policy + "/zzmoove/freq_limit_sleep", cpu.fitFrequency(729600) + "");
						k.trySetNode(policy + "/zzmoove/grad_up_threshold", "50");
						k.trySetNode(policy + "/zzmoove/grad_up_threshold_sleep", "28");
						k.trySetNode(policy + "/zzmoove/hotplug_block_up_cycles", "2");
						k.trySetNode(policy + "/zzmoove/block_up_multiplier_hotplug1", "1");
						k.trySetNode(policy + "/zzmoove/block_up_multiplier_hotplug2", "1");
						k.trySetNode(policy + "/zzmoove/block_up_multiplier_hotplug3", "1");
						k.trySetNode(policy + "/zzmoove/block_up_multiplier_hotplug4", "1");
						k.trySetNode(policy + "/zzmoove/block_up_multiplier_hotplug5", "1");
						k.trySetNode(policy + "/zzmoove/block_up_multiplier_hotplug6", "1");
						k.trySetNode(policy + "/zzmoove/block_up_multiplier_hotplug7", "1");
						k.trySetNode(policy + "/zzmoove/hotplug_block_down_cycles", "20");
						k.trySetNode(policy + "/zzmoove/block_down_multiplier_hotplug1", "1");
						k.trySetNode(policy + "/zzmoove/block_down_multiplier_hotplug2", "1");
						k.trySetNode(policy + "/zzmoove/block_down_multiplier_hotplug3", "1");
						k.trySetNode(policy + "/zzmoove/block_down_multiplier_hotplug4", "1");
						k.trySetNode(policy + "/zzmoove/block_down_multiplier_hotplug5", "1");
						k.trySetNode(policy + "/zzmoove/block_down_multiplier_hotplug6", "1");
						k.trySetNode(policy + "/zzmoove/block_down_multiplier_hotplug7", "1");
						k.trySetNode(policy + "/zzmoove/hotplug_stagger_up", "0");
						k.trySetNode(policy + "/zzmoove/hotplug_stagger_down", "0");
						k.trySetNode(policy + "/zzmoove/hotplug_idle_threshold", "0");
						k.trySetNode(policy + "/zzmoove/hotplug_idle_freq", "0");
						k.trySetNode(policy + "/zzmoove/hotplug_sleep", "1");
						k.trySetNode(policy + "/zzmoove/hotplug_engage_freq", "0");
						k.trySetNode(policy + "/zzmoove/hotplug_max_limit", "0");
						k.trySetNode(policy + "/zzmoove/hotplug_min_limit", "0");
						k.trySetNode(policy + "/zzmoove/hotplug_lock", "0");
						k.trySetNode(policy + "/zzmoove/ignore_nice_load", "0");
						k.trySetNode(policy + "/zzmoove/sampling_down_factor", "1");
						k.trySetNode(policy + "/zzmoove/sampling_down_max_momentum", "0");
						k.trySetNode(policy + "/zzmoove/sampling_down_momentum_sensitivity", "50");
						k.trySetNode(policy + "/zzmoove/sampling_rate", "100000");
						k.trySetNode(policy + "/zzmoove/sampling_rate_idle", "180000");
						k.trySetNode(policy + "/zzmoove/sampling_rate_idle_delay", "0");
						k.trySetNode(policy + "/zzmoove/sampling_rate_idle_threshold", "40");
						k.trySetNode(policy + "/zzmoove/sampling_rate_sleep_multiplier", "4");
						k.trySetNode(policy + "/zzmoove/scaling_block_cycles", "0");
						k.trySetNode(policy + "/zzmoove/scaling_block_temp", "0");
						k.trySetNode(policy + "/zzmoove/scaling_trip_temp", "0");
						k.trySetNode(policy + "/zzmoove/scaling_block_freq", "0");
						k.trySetNode(policy + "/zzmoove/scaling_block_threshold", "0");
						k.trySetNode(policy + "/zzmoove/scaling_block_force_down", "2");
						k.trySetNode(policy + "/zzmoove/scaling_fastdown_freq", cpu.fitFrequency(1958400) + "");
						k.trySetNode(policy + "/zzmoove/scaling_fastdown_up_threshold", "95");
						k.trySetNode(policy + "/zzmoove/scaling_fastdown_down_threshold", "90");
						k.trySetNode(policy + "/zzmoove/scaling_responsiveness_freq", cpu.fitFrequency(652800) + "");
						k.trySetNode(policy + "/zzmoove/scaling_responsiveness_up_threshold", "20");
						k.trySetNode(policy + "/zzmoove/scaling_proportional", "1");
						k.trySetNode(policy + "/zzmoove/inputboost_cycles", "0");
						k.trySetNode(policy + "/zzmoove/inputboost_up_threshold", "80");
						k.trySetNode(policy + "/zzmoove/inputboost_punch_cycles", "20");
						k.trySetNode(policy + "/zzmoove/inputboost_punch_freq", cpu.fitFrequency(1728000) + "");
						k.trySetNode(policy + "/zzmoove/inputboost_punch_on_fingerdown", "1");
						k.trySetNode(policy + "/zzmoove/inputboost_punch_on_fingermove", "0");
						k.trySetNode(policy + "/zzmoove/inputboost_punch_on_epenmove", "0");
						k.trySetNode(policy + "/zzmoove/inputboost_typingbooster_up_threshold", "40");
						k.trySetNode(policy + "/zzmoove/inputboost_typingbooster_cores", "3");
						k.trySetNode(policy + "/zzmoove/music_max_freq", cpu.fitFrequency(1497600) + "");
						k.trySetNode(policy + "/zzmoove/music_min_freq", cpu.fitFrequency(422400) + "");
						k.trySetNode(policy + "/zzmoove/music_min_cores", "2");
						k.trySetNode(policy + "/zzmoove/smooth_up", "75");
						k.trySetNode(policy + "/zzmoove/smooth_up_sleep", "100");
						k.trySetNode(policy + "/zzmoove/up_threshold", "95");
						k.trySetNode(policy + "/zzmoove/up_threshold_hotplug1", "60");
						k.trySetNode(policy + "/zzmoove/up_threshold_hotplug2", "80");
						k.trySetNode(policy + "/zzmoove/up_threshold_hotplug3", "98");
						k.trySetNode(policy + "/zzmoove/up_threshold_hotplug4", "68");
						k.trySetNode(policy + "/zzmoove/up_threshold_hotplug5", "68");
						k.trySetNode(policy + "/zzmoove/up_threshold_hotplug6", "68");
						k.trySetNode(policy + "/zzmoove/up_threshold_hotplug7", "68");
						k.trySetNode(policy + "/zzmoove/up_threshold_hotplug_freq1", cpu.fitFrequency(729600) + "");
						k.trySetNode(policy + "/zzmoove/up_threshold_hotplug_freq2", cpu.fitFrequency(1190400) + "");
						k.trySetNode(policy + "/zzmoove/up_threshold_hotplug_freq3", cpu.fitFrequency(1574400) + "");
						k.trySetNode(policy + "/zzmoove/up_threshold_hotplug_freq4", "0");
						k.trySetNode(policy + "/zzmoove/up_threshold_hotplug_freq5", "0");
						k.trySetNode(policy + "/zzmoove/up_threshold_hotplug_freq6", "0");
						k.trySetNode(policy + "/zzmoove/up_threshold_hotplug_freq7", "0");
						k.trySetNode(policy + "/zzmoove/up_threshold_sleep", "100");
						break;
					case "ondemand":
						// should never happen
						break;
				}
				break;
			case PROFILE_BANLANCED: // balanced
				switch (governor) {
					case "interactive":
						k.trySetNode(policy + "/interactive/hispeed_freq", cpu.fitPercentage(0.8) + "");
						k.trySetNode(policy + "/interactive/sync_freq", cpu.fitPercentage(0.4) + "");
						k.trySetNode(policy + "/interactive/up_threshold_any_cpu_freq", cpu.fitPercentage(0.9) + "");
						k.trySetNode(policy + "/blu_active/above_hispeed_delay",
								"20000 " + cpu.fitPercentage(0.5) + ":40000 " +
										cpu.fitPercentage(0.7) + ":20000 " +
										cpu.fitPercentage(0.85) + ":80000 " +
										cpu.fitPercentage(1) + ":100000");
						k.trySetNode(policy + "/interactive/go_hispeed_load", "95");
						k.trySetNode(policy + "/blu_active/target_loads",
								"85 " + cpu.fitPercentage(0.6) + ":90 " +
										cpu.fitPercentage(0.8) + ":70 " +
										cpu.fitPercentage(0.9) + ":95");
						k.trySetNode(policy + "/interactive/up_threshold_any_cpu_load", "80");
						k.trySetNode(policy + "/interactive/io_is_busy", "0");
						k.trySetNode(policy + "/interactive/timer_rate", "20000");
						k.trySetNode(policy + "/interactive/timer_slack", "20000");
						k.trySetNode(policy + "/interactive/min_sample_time", "80000");
						k.trySetNode(policy + "/interactive/boostpulse_duration", "80000");
						k.trySetNode(policy + "/interactive/use_migration_notif", "1");
						k.trySetNode(policy + "/interactive/ignore_hispeed_on_notif", "1");
						k.trySetNode(policy + "/interactive/max_freq_hysteresis", "80000");
						break;
					case "blu_active":
						k.trySetNode(policy + "/blu_active/above_hispeed_delay",
								"20000 " + cpu.fitPercentage(0.5) + ":40000 " +
										cpu.fitPercentage(0.7) + ":20000 " +
										cpu.fitPercentage(0.85) + ":80000 " +
										cpu.fitPercentage(1) + ":100000");
						k.trySetNode(policy + "/blu_active/align_windows", "1");
						k.trySetNode(policy + "/blu_active/fastlane", "1");
						k.trySetNode(policy + "/blu_active/fastlane_threshold", "80");
						k.trySetNode(policy + "/blu_active/go_hispeed_load", "90");
						k.trySetNode(policy + "/blu_active/hispeed_freq", cpu.fitPercentage(0.5) + "");
						k.trySetNode(policy + "/blu_active/io_is_busy", "0");
						k.trySetNode(policy + "/blu_active/min_sample_time", "20000");
						k.trySetNode(policy + "/blu_active/target_loads",
								"85 " + cpu.fitPercentage(0.6) + ":90 " +
										cpu.fitPercentage(0.8) + ":70 " +
										cpu.fitPercentage(0.9) + ":95");
						k.trySetNode(policy + "/blu_active/timer_rate", "20000");
						k.trySetNode(policy + "/blu_active/timer_slack", "20000");
						break;
					case "zzmoove":
						k.trySetNode(policy + "/zzmoove/auto_adjust_freq_thresholds", "0");
						k.trySetNode(policy + "/zzmoove/disable_hotplug", "1");
						k.trySetNode(policy + "/zzmoove/disable_hotplug_sleep", "1");
						k.trySetNode(policy + "/zzmoove/down_threshold", "52");
						k.trySetNode(policy + "/zzmoove/down_threshold_hotplug1", "55");
						k.trySetNode(policy + "/zzmoove/down_threshold_hotplug2", "55");
						k.trySetNode(policy + "/zzmoove/down_threshold_hotplug3", "55");
						k.trySetNode(policy + "/zzmoove/down_threshold_hotplug4", "55");
						k.trySetNode(policy + "/zzmoove/down_threshold_hotplug5", "55");
						k.trySetNode(policy + "/zzmoove/down_threshold_hotplug6", "55");
						k.trySetNode(policy + "/zzmoove/down_threshold_hotplug7", "55");
						k.trySetNode(policy + "/zzmoove/down_threshold_hotplug_freq1", "0");
						k.trySetNode(policy + "/zzmoove/down_threshold_hotplug_freq2", "0");
						k.trySetNode(policy + "/zzmoove/down_threshold_hotplug_freq3", "0");
						k.trySetNode(policy + "/zzmoove/down_threshold_hotplug_freq4", "0");
						k.trySetNode(policy + "/zzmoove/down_threshold_hotplug_freq5", "0");
						k.trySetNode(policy + "/zzmoove/down_threshold_hotplug_freq6", "0");
						k.trySetNode(policy + "/zzmoove/down_threshold_hotplug_freq7", "0");
						k.trySetNode(policy + "/zzmoove/down_threshold_sleep", "44");
						k.trySetNode(policy + "/zzmoove/early_demand", "0");
						k.trySetNode(policy + "/zzmoove/early_demand_sleep", "1");
						k.trySetNode(policy + "/zzmoove/fast_scaling_up", "0");
						k.trySetNode(policy + "/zzmoove/fast_scaling_down", "0");
						k.trySetNode(policy + "/zzmoove/fast_scaling_sleep_up", "0");
						k.trySetNode(policy + "/zzmoove/fast_scaling_sleep_down", "0");
						k.trySetNode(policy + "/zzmoove/autofastscalingstepone", "25");
						k.trySetNode(policy + "/zzmoove/autofastscalingsteptwo", "50");
						k.trySetNode(policy + "/zzmoove/autofastscalingstepthree", "75");
						k.trySetNode(policy + "/zzmoove/autofastscalingstepfour", "90");
						k.trySetNode(policy + "/zzmoove/freq_limit", "0");
						k.trySetNode(policy + "/zzmoove/freq_limit_sleep", "0");
						k.trySetNode(policy + "/zzmoove/grad_up_threshold", "25");
						k.trySetNode(policy + "/zzmoove/grad_up_threshold_sleep", "28");
						k.trySetNode(policy + "/zzmoove/hotplug_block_up_cycles", "2");
						k.trySetNode(policy + "/zzmoove/block_up_multiplier_hotplug1", "1");
						k.trySetNode(policy + "/zzmoove/block_up_multiplier_hotplug2", "1");
						k.trySetNode(policy + "/zzmoove/block_up_multiplier_hotplug3", "1");
						k.trySetNode(policy + "/zzmoove/block_up_multiplier_hotplug4", "1");
						k.trySetNode(policy + "/zzmoove/block_up_multiplier_hotplug5", "1");
						k.trySetNode(policy + "/zzmoove/block_up_multiplier_hotplug6", "1");
						k.trySetNode(policy + "/zzmoove/block_up_multiplier_hotplug7", "1");
						k.trySetNode(policy + "/zzmoove/hotplug_block_down_cycles", "20");
						k.trySetNode(policy + "/zzmoove/block_down_multiplier_hotplug1", "1");
						k.trySetNode(policy + "/zzmoove/block_down_multiplier_hotplug2", "1");
						k.trySetNode(policy + "/zzmoove/block_down_multiplier_hotplug3", "1");
						k.trySetNode(policy + "/zzmoove/block_down_multiplier_hotplug4", "1");
						k.trySetNode(policy + "/zzmoove/block_down_multiplier_hotplug5", "1");
						k.trySetNode(policy + "/zzmoove/block_down_multiplier_hotplug6", "1");
						k.trySetNode(policy + "/zzmoove/block_down_multiplier_hotplug7", "1");
						k.trySetNode(policy + "/zzmoove/hotplug_stagger_up", "0");
						k.trySetNode(policy + "/zzmoove/hotplug_stagger_down", "0");
						k.trySetNode(policy + "/zzmoove/hotplug_idle_threshold", "0");
						k.trySetNode(policy + "/zzmoove/hotplug_idle_freq", "0");
						k.trySetNode(policy + "/zzmoove/hotplug_sleep", "0");
						k.trySetNode(policy + "/zzmoove/hotplug_engage_freq", "0");
						k.trySetNode(policy + "/zzmoove/hotplug_max_limit", "0");
						k.trySetNode(policy + "/zzmoove/hotplug_min_limit", "0");
						k.trySetNode(policy + "/zzmoove/hotplug_lock", "0");
						k.trySetNode(policy + "/zzmoove/ignore_nice_load", "0");
						k.trySetNode(policy + "/zzmoove/sampling_down_factor", "1");
						k.trySetNode(policy + "/zzmoove/sampling_down_max_momentum", "0");
						k.trySetNode(policy + "/zzmoove/sampling_down_momentum_sensitivity", "50");
						k.trySetNode(policy + "/zzmoove/sampling_rate", "100000");
						k.trySetNode(policy + "/zzmoove/sampling_rate_idle", "180000");
						k.trySetNode(policy + "/zzmoove/sampling_rate_idle_delay", "0");
						k.trySetNode(policy + "/zzmoove/sampling_rate_idle_threshold", "40");
						k.trySetNode(policy + "/zzmoove/sampling_rate_sleep_multiplier", "2");
						k.trySetNode(policy + "/zzmoove/scaling_block_cycles", "0");
						k.trySetNode(policy + "/zzmoove/scaling_block_temp", "0");
						k.trySetNode(policy + "/zzmoove/scaling_trip_temp", "0");
						k.trySetNode(policy + "/zzmoove/scaling_block_freq", cpu.fitFrequency(1728000) + "");
						k.trySetNode(policy + "/zzmoove/scaling_block_threshold", "10");
						k.trySetNode(policy + "/zzmoove/scaling_block_force_down", "2");
						k.trySetNode(policy + "/zzmoove/scaling_fastdown_freq", "0");
						k.trySetNode(policy + "/zzmoove/scaling_fastdown_up_threshold", "95");
						k.trySetNode(policy + "/zzmoove/scaling_fastdown_down_threshold", "90");
						k.trySetNode(policy + "/zzmoove/scaling_responsiveness_freq", "0");
						k.trySetNode(policy + "/zzmoove/scaling_responsiveness_up_threshold", "30");
						k.trySetNode(policy + "/zzmoove/scaling_proportional", "0");
						k.trySetNode(policy + "/zzmoove/inputboost_cycles", "0");
						k.trySetNode(policy + "/zzmoove/inputboost_up_threshold", "80");
						k.trySetNode(policy + "/zzmoove/inputboost_punch_cycles", "20");
						k.trySetNode(policy + "/zzmoove/inputboost_punch_freq", cpu.fitFrequency(1728000) + "");
						k.trySetNode(policy + "/zzmoove/inputboost_punch_on_fingerdown", "1");
						k.trySetNode(policy + "/zzmoove/inputboost_punch_on_fingermove", "0");
						k.trySetNode(policy + "/zzmoove/inputboost_punch_on_epenmove", "0");
						k.trySetNode(policy + "/zzmoove/inputboost_typingbooster_up_threshold", "40");
						k.trySetNode(policy + "/zzmoove/inputboost_typingbooster_cores", "3");
						k.trySetNode(policy + "/zzmoove/music_max_freq", cpu.fitFrequency(1497600) + "");
						k.trySetNode(policy + "/zzmoove/music_min_freq", cpu.fitFrequency(422400) + "");
						k.trySetNode(policy + "/zzmoove/music_min_cores", "2");
						k.trySetNode(policy + "/zzmoove/smooth_up", "75");
						k.trySetNode(policy + "/zzmoove/smooth_up_sleep", "100");
						k.trySetNode(policy + "/zzmoove/up_threshold", "70");
						k.trySetNode(policy + "/zzmoove/up_threshold_hotplug1", "68");
						k.trySetNode(policy + "/zzmoove/up_threshold_hotplug2", "68");
						k.trySetNode(policy + "/zzmoove/up_threshold_hotplug3", "68");
						k.trySetNode(policy + "/zzmoove/up_threshold_hotplug4", "68");
						k.trySetNode(policy + "/zzmoove/up_threshold_hotplug5", "68");
						k.trySetNode(policy + "/zzmoove/up_threshold_hotplug6", "68");
						k.trySetNode(policy + "/zzmoove/up_threshold_hotplug7", "68");
						k.trySetNode(policy + "/zzmoove/up_threshold_hotplug_freq1", "0");
						k.trySetNode(policy + "/zzmoove/up_threshold_hotplug_freq2", "0");
						k.trySetNode(policy + "/zzmoove/up_threshold_hotplug_freq3", "0");
						k.trySetNode(policy + "/zzmoove/up_threshold_hotplug_freq4", "0");
						k.trySetNode(policy + "/zzmoove/up_threshold_hotplug_freq5", "0");
						k.trySetNode(policy + "/zzmoove/up_threshold_hotplug_freq6", "0");
						k.trySetNode(policy + "/zzmoove/up_threshold_hotplug_freq7", "0");
						k.trySetNode(policy + "/zzmoove/up_threshold_sleep", "90");
						break;
					case "ondemand":
						// should never happen
						break;
				}
				break;
			case PROFILE_PERFORMANCE: // performance
				switch (governor) {
					case "interactive":
						k.trySetNode(policy + "/blu_active/above_hispeed_delay",
								"20000 " + cpu.fitPercentage(0.5) + ":40000 " +
										cpu.fitPercentage(0.7) + ":20000 " +
										cpu.fitPercentage(0.85) + ":80000 " +
										cpu.fitPercentage(1) + ":100000");
						k.trySetNode(policy + "/interactive/hispeed_freq", cpu.fitPercentage(0.6) + "");
						k.trySetNode(policy + "/interactive/sync_freq", cpu.fitPercentage(0.4) + "");
						k.trySetNode(policy + "/interactive/up_threshold_any_cpu_freq", cpu.fitPercentage(0.8) + "");
						k.trySetNode(policy + "/interactive/go_hispeed_load", "80");
						k.trySetNode(policy + "/interactive/target_loads", "50");
						k.trySetNode(policy + "/interactive/up_threshold_any_cpu_load", "70");
						k.trySetNode(policy + "/interactive/io_is_busy", "1");
						k.trySetNode(policy + "/interactive/timer_rate", "20000");
						k.trySetNode(policy + "/interactive/timer_slack", "20000");
						k.trySetNode(policy + "/interactive/min_sample_time", "80000");
						k.trySetNode(policy + "/interactive/boostpulse_duration", "80000");
						k.trySetNode(policy + "/interactive/use_migration_notif", "1");
						k.trySetNode(policy + "/interactive/ignore_hispeed_on_notif", "1");
						k.trySetNode(policy + "/interactive/max_freq_hysteresis", "40000");
						break;
					case "blu_active":
						k.trySetNode(policy + "/blu_active/above_hispeed_delay",
								"20000 " + cpu.fitPercentage(0.5) + ":40000 " +
										cpu.fitPercentage(0.7) + ":20000 " +
										cpu.fitPercentage(0.85) + ":80000 " +
										cpu.fitPercentage(1) + ":100000");
						k.trySetNode(policy + "/blu_active/align_windows", "1");
						k.trySetNode(policy + "/blu_active/fastlane", "1");
						k.trySetNode(policy + "/blu_active/fastlane_threshold", "25");
						k.trySetNode(policy + "/blu_active/go_hispeed_load", "80");
						k.trySetNode(policy + "/blu_active/hispeed_freq", cpu.fitPercentage(0.6) + "");
						k.trySetNode(policy + "/blu_active/io_is_busy", "0");
						k.trySetNode(policy + "/blu_active/min_sample_time", "20000");
						k.trySetNode(policy + "/blu_active/target_loads", "50");
						k.trySetNode(policy + "/blu_active/timer_rate", "20000");
						k.trySetNode(policy + "/blu_active/timer_slack", "20000");
						break;
					case "zzmoove":
						k.trySetNode(policy + "/zzmoove/auto_adjust_freq_thresholds", "0");
						k.trySetNode(policy + "/zzmoove/disable_hotplug", "1");
						k.trySetNode(policy + "/zzmoove/disable_hotplug_sleep", "1");
						k.trySetNode(policy + "/zzmoove/down_threshold", "20");
						k.trySetNode(policy + "/zzmoove/down_threshold_hotplug1", "25");
						k.trySetNode(policy + "/zzmoove/down_threshold_hotplug2", "35");
						k.trySetNode(policy + "/zzmoove/down_threshold_hotplug3", "45");
						k.trySetNode(policy + "/zzmoove/down_threshold_hotplug4", "55");
						k.trySetNode(policy + "/zzmoove/down_threshold_hotplug5", "55");
						k.trySetNode(policy + "/zzmoove/down_threshold_hotplug6", "55");
						k.trySetNode(policy + "/zzmoove/down_threshold_hotplug7", "55");
						k.trySetNode(policy + "/zzmoove/down_threshold_hotplug_freq1", cpu.fitFrequency(300000) + "");
						k.trySetNode(policy + "/zzmoove/down_threshold_hotplug_freq2", cpu.fitFrequency(1190400) + "");
						k.trySetNode(policy + "/zzmoove/down_threshold_hotplug_freq3", cpu.fitFrequency(1574400) + "");
						k.trySetNode(policy + "/zzmoove/down_threshold_hotplug_freq4", "0");
						k.trySetNode(policy + "/zzmoove/down_threshold_hotplug_freq5", "0");
						k.trySetNode(policy + "/zzmoove/down_threshold_hotplug_freq6", "0");
						k.trySetNode(policy + "/zzmoove/down_threshold_hotplug_freq7", "0");
						k.trySetNode(policy + "/zzmoove/down_threshold_sleep", "60");
						k.trySetNode(policy + "/zzmoove/early_demand", "1");
						k.trySetNode(policy + "/zzmoove/early_demand_sleep", "1");
						k.trySetNode(policy + "/zzmoove/fast_scaling_up", "1");
						k.trySetNode(policy + "/zzmoove/fast_scaling_down", "1");
						k.trySetNode(policy + "/zzmoove/fast_scaling_sleep_up", "2");
						k.trySetNode(policy + "/zzmoove/fast_scaling_sleep_down", "0");
						k.trySetNode(policy + "/zzmoove/afs_threshold1", "30");
						k.trySetNode(policy + "/zzmoove/afs_threshold2", "50");
						k.trySetNode(policy + "/zzmoove/afs_threshold3", "70");
						k.trySetNode(policy + "/zzmoove/afs_threshold4", "90");
						k.trySetNode(policy + "/zzmoove/freq_limit", "0");
						k.trySetNode(policy + "/zzmoove/freq_limit_sleep", cpu.fitFrequency(729600) + "");
						k.trySetNode(policy + "/zzmoove/grad_up_threshold", "25");
						k.trySetNode(policy + "/zzmoove/grad_up_threshold_sleep", "28");
						k.trySetNode(policy + "/zzmoove/hotplug_block_up_cycles", "2");
						k.trySetNode(policy + "/zzmoove/block_up_multiplier_hotplug1", "1");
						k.trySetNode(policy + "/zzmoove/block_up_multiplier_hotplug2", "1");
						k.trySetNode(policy + "/zzmoove/block_up_multiplier_hotplug3", "1");
						k.trySetNode(policy + "/zzmoove/block_up_multiplier_hotplug4", "1");
						k.trySetNode(policy + "/zzmoove/block_up_multiplier_hotplug5", "1");
						k.trySetNode(policy + "/zzmoove/block_up_multiplier_hotplug6", "1");
						k.trySetNode(policy + "/zzmoove/block_up_multiplier_hotplug7", "1");
						k.trySetNode(policy + "/zzmoove/hotplug_block_down_cycles", "20");
						k.trySetNode(policy + "/zzmoove/block_down_multiplier_hotplug1", "1");
						k.trySetNode(policy + "/zzmoove/block_down_multiplier_hotplug2", "1");
						k.trySetNode(policy + "/zzmoove/block_down_multiplier_hotplug3", "1");
						k.trySetNode(policy + "/zzmoove/block_down_multiplier_hotplug4", "1");
						k.trySetNode(policy + "/zzmoove/block_down_multiplier_hotplug5", "1");
						k.trySetNode(policy + "/zzmoove/block_down_multiplier_hotplug6", "1");
						k.trySetNode(policy + "/zzmoove/block_down_multiplier_hotplug7", "1");
						k.trySetNode(policy + "/zzmoove/hotplug_stagger_up", "0");
						k.trySetNode(policy + "/zzmoove/hotplug_stagger_down", "0");
						k.trySetNode(policy + "/zzmoove/hotplug_idle_threshold", "0");
						k.trySetNode(policy + "/zzmoove/hotplug_idle_freq", "0");
						k.trySetNode(policy + "/zzmoove/hotplug_sleep", "1");
						k.trySetNode(policy + "/zzmoove/hotplug_engage_freq", "0");
						k.trySetNode(policy + "/zzmoove/hotplug_max_limit", "0");
						k.trySetNode(policy + "/zzmoove/hotplug_min_limit", "0");
						k.trySetNode(policy + "/zzmoove/hotplug_lock", "0");
						k.trySetNode(policy + "/zzmoove/ignore_nice_load", "0");
						k.trySetNode(policy + "/zzmoove/sampling_down_factor", "4");
						k.trySetNode(policy + "/zzmoove/sampling_down_max_momentum", "50");
						k.trySetNode(policy + "/zzmoove/sampling_down_momentum_sensitivity", "25");
						k.trySetNode(policy + "/zzmoove/sampling_rate", "60000");
						k.trySetNode(policy + "/zzmoove/sampling_rate_idle", "100000");
						k.trySetNode(policy + "/zzmoove/sampling_rate_idle_delay", "0");
						k.trySetNode(policy + "/zzmoove/sampling_rate_idle_threshold", "40");
						k.trySetNode(policy + "/zzmoove/sampling_rate_sleep_multiplier", "4");
						k.trySetNode(policy + "/zzmoove/scaling_block_cycles", "0");
						k.trySetNode(policy + "/zzmoove/scaling_block_temp", "0");
						k.trySetNode(policy + "/zzmoove/scaling_trip_temp", "0");
						k.trySetNode(policy + "/zzmoove/scaling_block_freq", "0");
						k.trySetNode(policy + "/zzmoove/scaling_block_threshold", "0");
						k.trySetNode(policy + "/zzmoove/scaling_block_force_down", "2");
						k.trySetNode(policy + "/zzmoove/scaling_fastdown_freq", "0");
						k.trySetNode(policy + "/zzmoove/scaling_fastdown_up_threshold", "95");
						k.trySetNode(policy + "/zzmoove/scaling_fastdown_down_threshold", "90");
						k.trySetNode(policy + "/zzmoove/scaling_responsiveness_freq", "0");
						k.trySetNode(policy + "/zzmoove/scaling_responsiveness_up_threshold", "0");
						k.trySetNode(policy + "/zzmoove/scaling_proportional", "0");
						k.trySetNode(policy + "/zzmoove/inputboost_cycles", "0");
						k.trySetNode(policy + "/zzmoove/inputboost_up_threshold", "80");
						k.trySetNode(policy + "/zzmoove/inputboost_punch_cycles", "20");
						k.trySetNode(policy + "/zzmoove/inputboost_punch_freq", cpu.fitFrequency(1728000) + "");
						k.trySetNode(policy + "/zzmoove/inputboost_punch_on_fingerdown", "1");
						k.trySetNode(policy + "/zzmoove/inputboost_punch_on_fingermove", "0");
						k.trySetNode(policy + "/zzmoove/inputboost_punch_on_epenmove", "0");
						k.trySetNode(policy + "/zzmoove/inputboost_typingbooster_up_threshold", "40");
						k.trySetNode(policy + "/zzmoove/inputboost_typingbooster_cores", "3");
						k.trySetNode(policy + "/zzmoove/music_max_freq", cpu.fitFrequency(1497600) + "");
						k.trySetNode(policy + "/zzmoove/music_min_freq", cpu.fitFrequency(422400) + "");
						k.trySetNode(policy + "/zzmoove/music_min_cores", "2");
						k.trySetNode(policy + "/zzmoove/smooth_up", "70");
						k.trySetNode(policy + "/zzmoove/smooth_up_sleep", "100");
						k.trySetNode(policy + "/zzmoove/up_threshold", "60");
						k.trySetNode(policy + "/zzmoove/up_threshold_hotplug1", "65");
						k.trySetNode(policy + "/zzmoove/up_threshold_hotplug2", "75");
						k.trySetNode(policy + "/zzmoove/up_threshold_hotplug3", "85");
						k.trySetNode(policy + "/zzmoove/up_threshold_hotplug4", "68");
						k.trySetNode(policy + "/zzmoove/up_threshold_hotplug5", "68");
						k.trySetNode(policy + "/zzmoove/up_threshold_hotplug6", "68");
						k.trySetNode(policy + "/zzmoove/up_threshold_hotplug7", "68");
						k.trySetNode(policy + "/zzmoove/up_threshold_hotplug_freq1", cpu.fitFrequency(422400) + "");
						k.trySetNode(policy + "/zzmoove/up_threshold_hotplug_freq2", cpu.fitFrequency(1267200) + "");
						k.trySetNode(policy + "/zzmoove/up_threshold_hotplug_freq3", cpu.fitFrequency(1728000) + "");
						k.trySetNode(policy + "/zzmoove/up_threshold_hotplug_freq4", "0");
						k.trySetNode(policy + "/zzmoove/up_threshold_hotplug_freq5", "0");
						k.trySetNode(policy + "/zzmoove/up_threshold_hotplug_freq6", "0");
						k.trySetNode(policy + "/zzmoove/up_threshold_hotplug_freq7", "0");
						k.trySetNode(policy + "/zzmoove/up_threshold_sleep", "100");
						break;
					case "ondemand":
						k.trySetNode(policy + "/ondemand/down_differential", "2");
						k.trySetNode(policy + "/ondemand/ignore_nice_load", "0");
						k.trySetNode(policy + "/ondemand/input_boost", "1");
						k.trySetNode(policy + "/ondemand/io_is_busy", "0");
						k.trySetNode(policy + "/ondemand/optimal_freq", cpu.fitPercentage(0.6) + "");
						k.trySetNode(policy + "/ondemand/powersave_bias", "0");
						k.trySetNode(policy + "/ondemand/sampling_down_factor", "1");
						k.trySetNode(policy + "/ondemand/sampling_rate", "20000");
						k.trySetNode(policy + "/ondemand/sampling_rate_min", "10000");
						k.trySetNode(policy + "/ondemand/sync_freq", cpu.fitPercentage(0.5) + "");
						k.trySetNode(policy + "/ondemand/up_threshold", "90");
						k.trySetNode(policy + "/ondemand/up_threshold_any_cpu_load", "90");
						k.trySetNode(policy + "/ondemand/up_threshold_multi_core", "80");
						break;
				}
			case PROFILE_GAMING: //gaming
				switch (governor) {
					case "interactive":
						k.trySetNode(policy + "/interactive/hispeed_freq", cpu.fitPercentage(0.8) + "");
						k.trySetNode(policy + "/interactive/sync_freq", cpu.fitPercentage(0.5) + "");
						k.trySetNode(policy + "/interactive/up_threshold_any_cpu_freq", cpu.fitPercentage(0.7) + "");
						k.trySetNode(policy + "/blu_active/above_hispeed_delay",
								"20000 " + cpu.fitPercentage(0.5) + ":40000 " +
										cpu.fitPercentage(0.7) + ":20000 " +
										cpu.fitPercentage(0.85) + ":80000 " +
										cpu.fitPercentage(1) + ":100000");
						k.trySetNode(policy + "/interactive/go_hispeed_load", "80");
						k.trySetNode(policy + "/interactive/target_loads", "50");
						k.trySetNode(policy + "/interactive/up_threshold_any_cpu_load", "70");
						k.trySetNode(policy + "/interactive/io_is_busy", "1");
						k.trySetNode(policy + "/interactive/timer_rate", "20000");
						k.trySetNode(policy + "/interactive/timer_slack", "20000");
						k.trySetNode(policy + "/interactive/min_sample_time", "80000");
						k.trySetNode(policy + "/interactive/boostpulse_duration", "80000");
						break;
					case "blu_active":
						k.trySetNode(policy + "/blu_active/above_hispeed_delay",
								"20000 " + cpu.fitPercentage(0.5) + ":40000 " +
										cpu.fitPercentage(0.7) + ":20000 " +
										cpu.fitPercentage(0.85) + ":80000 " +
										cpu.fitPercentage(1) + ":100000");
						k.trySetNode(policy + "/blu_active/align_windows", "1");
						k.trySetNode(policy + "/blu_active/fastlane", "1");
						k.trySetNode(policy + "/blu_active/fastlane_threshold", "25");
						k.trySetNode(policy + "/blu_active/go_hispeed_load", "80");
						k.trySetNode(policy + "/blu_active/hispeed_freq", cpu.fitPercentage(0.8) + "");
						k.trySetNode(policy + "/blu_active/io_is_busy", "0");
						k.trySetNode(policy + "/blu_active/min_sample_time", "20000");
						k.trySetNode(policy + "/blu_active/target_loads", "50");
						k.trySetNode(policy + "/blu_active/timer_rate", "20000");
						k.trySetNode(policy + "/blu_active/timer_slack", "20000");
						break;
					case "zzmoove":
						k.trySetNode(policy + "/zzmoove/auto_adjust_freq_thresholds", "0");
						k.trySetNode(policy + "/zzmoove/disable_hotplug", "1");
						k.trySetNode(policy + "/zzmoove/disable_hotplug_sleep", "1");
						k.trySetNode(policy + "/zzmoove/down_threshold", "20");
						k.trySetNode(policy + "/zzmoove/down_threshold_hotplug1", "25");
						k.trySetNode(policy + "/zzmoove/down_threshold_hotplug2", "35");
						k.trySetNode(policy + "/zzmoove/down_threshold_hotplug3", "45");
						k.trySetNode(policy + "/zzmoove/down_threshold_hotplug4", "55");
						k.trySetNode(policy + "/zzmoove/down_threshold_hotplug5", "55");
						k.trySetNode(policy + "/zzmoove/down_threshold_hotplug6", "55");
						k.trySetNode(policy + "/zzmoove/down_threshold_hotplug7", "55");
						k.trySetNode(policy + "/zzmoove/down_threshold_hotplug_freq1", cpu.fitFrequency(422400) + "");
						k.trySetNode(policy + "/zzmoove/down_threshold_hotplug_freq2", cpu.fitFrequency(1190400) + "");
						k.trySetNode(policy + "/zzmoove/down_threshold_hotplug_freq3", cpu.fitFrequency(1574400) + "");
						k.trySetNode(policy + "/zzmoove/down_threshold_hotplug_freq4", "0");
						k.trySetNode(policy + "/zzmoove/down_threshold_hotplug_freq5", "0");
						k.trySetNode(policy + "/zzmoove/down_threshold_hotplug_freq6", "0");
						k.trySetNode(policy + "/zzmoove/down_threshold_hotplug_freq7", "0");
						k.trySetNode(policy + "/zzmoove/down_threshold_sleep", "60");
						k.trySetNode(policy + "/zzmoove/early_demand", "1");
						k.trySetNode(policy + "/zzmoove/early_demand_sleep", "1");
						k.trySetNode(policy + "/zzmoove/fast_scaling_up", "0");
						k.trySetNode(policy + "/zzmoove/fast_scaling_down", "0");
						k.trySetNode(policy + "/zzmoove/fast_scaling_sleep_up", "2");
						k.trySetNode(policy + "/zzmoove/fast_scaling_sleep_down", "0");
						k.trySetNode(policy + "/zzmoove/afs_threshold1", "30");
						k.trySetNode(policy + "/zzmoove/afs_threshold2", "50");
						k.trySetNode(policy + "/zzmoove/afs_threshold3", "70");
						k.trySetNode(policy + "/zzmoove/afs_threshold4", "90");
						k.trySetNode(policy + "/zzmoove/freq_limit", "0");
						k.trySetNode(policy + "/zzmoove/freq_limit_sleep", cpu.fitFrequency(729600) + "");
						k.trySetNode(policy + "/zzmoove/grad_up_threshold", "25");
						k.trySetNode(policy + "/zzmoove/grad_up_threshold_sleep", "28");
						k.trySetNode(policy + "/zzmoove/hotplug_block_up_cycles", "2");
						k.trySetNode(policy + "/zzmoove/block_up_multiplier_hotplug1", "1");
						k.trySetNode(policy + "/zzmoove/block_up_multiplier_hotplug2", "1");
						k.trySetNode(policy + "/zzmoove/block_up_multiplier_hotplug3", "1");
						k.trySetNode(policy + "/zzmoove/block_up_multiplier_hotplug4", "1");
						k.trySetNode(policy + "/zzmoove/block_up_multiplier_hotplug5", "1");
						k.trySetNode(policy + "/zzmoove/block_up_multiplier_hotplug6", "1");
						k.trySetNode(policy + "/zzmoove/block_up_multiplier_hotplug7", "1");
						k.trySetNode(policy + "/zzmoove/hotplug_block_down_cycles", "20");
						k.trySetNode(policy + "/zzmoove/block_down_multiplier_hotplug1", "1");
						k.trySetNode(policy + "/zzmoove/block_down_multiplier_hotplug2", "1");
						k.trySetNode(policy + "/zzmoove/block_down_multiplier_hotplug3", "1");
						k.trySetNode(policy + "/zzmoove/block_down_multiplier_hotplug4", "1");
						k.trySetNode(policy + "/zzmoove/block_down_multiplier_hotplug5", "1");
						k.trySetNode(policy + "/zzmoove/block_down_multiplier_hotplug6", "1");
						k.trySetNode(policy + "/zzmoove/block_down_multiplier_hotplug7", "1");
						k.trySetNode(policy + "/zzmoove/hotplug_stagger_up", "0");
						k.trySetNode(policy + "/zzmoove/hotplug_stagger_down", "0");
						k.trySetNode(policy + "/zzmoove/hotplug_idle_threshold", "0");
						k.trySetNode(policy + "/zzmoove/hotplug_idle_freq", "0");
						k.trySetNode(policy + "/zzmoove/hotplug_sleep", "1");
						k.trySetNode(policy + "/zzmoove/hotplug_engage_freq", "0");
						k.trySetNode(policy + "/zzmoove/hotplug_max_limit", "0");
						k.trySetNode(policy + "/zzmoove/hotplug_min_limit", "0");
						k.trySetNode(policy + "/zzmoove/hotplug_lock", "0");
						k.trySetNode(policy + "/zzmoove/ignore_nice_load", "0");
						k.trySetNode(policy + "/zzmoove/sampling_down_factor", "4");
						k.trySetNode(policy + "/zzmoove/sampling_down_max_momentum", "60");
						k.trySetNode(policy + "/zzmoove/sampling_down_momentum_sensitivity", "20");
						k.trySetNode(policy + "/zzmoove/sampling_rate", "60000");
						k.trySetNode(policy + "/zzmoove/sampling_rate_idle", "100000");
						k.trySetNode(policy + "/zzmoove/sampling_rate_idle_delay", "0");
						k.trySetNode(policy + "/zzmoove/sampling_rate_idle_threshold", "40");
						k.trySetNode(policy + "/zzmoove/sampling_rate_sleep_multiplier", "4");
						k.trySetNode(policy + "/zzmoove/scaling_block_cycles", "0");
						k.trySetNode(policy + "/zzmoove/scaling_block_temp", "65");
						k.trySetNode(policy + "/zzmoove/scaling_block_cycles", "15");
						k.trySetNode(policy + "/zzmoove/scaling_trip_temp", "0");
						k.trySetNode(policy + "/zzmoove/scaling_block_freq", cpu.fitFrequency(1574400) + "");
						k.trySetNode(policy + "/zzmoove/scaling_block_threshold", "5");
						k.trySetNode(policy + "/zzmoove/scaling_block_force_down", "3");
						k.trySetNode(policy + "/zzmoove/scaling_fastdown_freq", "0");
						k.trySetNode(policy + "/zzmoove/scaling_fastdown_up_threshold", "95");
						k.trySetNode(policy + "/zzmoove/scaling_fastdown_down_threshold", "90");
						k.trySetNode(policy + "/zzmoove/scaling_responsiveness_freq", "0");
						k.trySetNode(policy + "/zzmoove/scaling_responsiveness_up_threshold", "0");
						k.trySetNode(policy + "/zzmoove/scaling_proportional", "1");
						k.trySetNode(policy + "/zzmoove/inputboost_cycles", "0");
						k.trySetNode(policy + "/zzmoove/inputboost_up_threshold", "80");
						k.trySetNode(policy + "/zzmoove/inputboost_punch_cycles", "20");
						k.trySetNode(policy + "/zzmoove/inputboost_punch_freq", cpu.fitFrequency(1728000) + "");
						k.trySetNode(policy + "/zzmoove/inputboost_punch_on_fingerdown", "1");
						k.trySetNode(policy + "/zzmoove/inputboost_punch_on_fingermove", "0");
						k.trySetNode(policy + "/zzmoove/inputboost_punch_on_epenmove", "0");
						k.trySetNode(policy + "/zzmoove/inputboost_typingbooster_up_threshold", "40");
						k.trySetNode(policy + "/zzmoove/inputboost_typingbooster_cores", "3");
						k.trySetNode(policy + "/zzmoove/music_max_freq", cpu.fitFrequency(1497600) + "");
						k.trySetNode(policy + "/zzmoove/music_min_freq", cpu.fitFrequency(422400) + "");
						k.trySetNode(policy + "/zzmoove/music_min_cores", "2");
						k.trySetNode(policy + "/zzmoove/smooth_up", "70");
						k.trySetNode(policy + "/zzmoove/smooth_up_sleep", "100");
						k.trySetNode(policy + "/zzmoove/up_threshold", "60");
						k.trySetNode(policy + "/zzmoove/up_threshold_hotplug1", "65");
						k.trySetNode(policy + "/zzmoove/up_threshold_hotplug2", "75");
						k.trySetNode(policy + "/zzmoove/up_threshold_hotplug3", "85");
						k.trySetNode(policy + "/zzmoove/up_threshold_hotplug4", "68");
						k.trySetNode(policy + "/zzmoove/up_threshold_hotplug5", "68");
						k.trySetNode(policy + "/zzmoove/up_threshold_hotplug6", "68");
						k.trySetNode(policy + "/zzmoove/up_threshold_hotplug7", "68");
						k.trySetNode(policy + "/zzmoove/up_threshold_hotplug_freq1", cpu.fitFrequency(652800) + "");
						k.trySetNode(policy + "/zzmoove/up_threshold_hotplug_freq2", cpu.fitFrequency(1267200) + "");
						k.trySetNode(policy + "/zzmoove/up_threshold_hotplug_freq3", cpu.fitFrequency(1958400) + "");
						k.trySetNode(policy + "/zzmoove/up_threshold_hotplug_freq4", "0");
						k.trySetNode(policy + "/zzmoove/up_threshold_hotplug_freq5", "0");
						k.trySetNode(policy + "/zzmoove/up_threshold_hotplug_freq6", "0");
						k.trySetNode(policy + "/zzmoove/up_threshold_hotplug_freq7", "0");
						k.trySetNode(policy + "/zzmoove/up_threshold_sleep", "100");
						break;
					case "ondemand":
						k.trySetNode(policy + "/ondemand/down_differential", "2");
						k.trySetNode(policy + "/ondemand/ignore_nice_load", "0");
						k.trySetNode(policy + "/ondemand/input_boost", "1");
						k.trySetNode(policy + "/ondemand/io_is_busy", "0");
						k.trySetNode(policy + "/ondemand/optimal_freq", cpu.fitPercentage(0.6) + "");
						k.trySetNode(policy + "/ondemand/powersave_bias", "0");
						k.trySetNode(policy + "/ondemand/sampling_down_factor", "1");
						k.trySetNode(policy + "/ondemand/sampling_rate", "20000");
						k.trySetNode(policy + "/ondemand/sampling_rate_min", "10000");
						k.trySetNode(policy + "/ondemand/sync_freq", cpu.fitPercentage(0.5) + "");
						k.trySetNode(policy + "/ondemand/up_threshold", "90");
						k.trySetNode(policy + "/ondemand/up_threshold_any_cpu_load", "90");
						k.trySetNode(policy + "/ondemand/up_threshold_multi_core", "80");
						break;
				}
		}
	}

	@Override
	public void onReceive(Context context, Intent intent) {
		try {
			MainActivity.loadProperties(context);
			if (MainActivity.properties.getProperty("smooth_interactive", "disabled").equals("enabled")) {
				try {
					tweak(Integer.parseInt(MainActivity.properties.getProperty("hotplug_profile", "0")),
							Integer.parseInt(MainActivity.properties.getProperty("interactive_profile", "1")));
					Toast.makeText(context, R.string.boot_success, Toast.LENGTH_SHORT).show();
				} catch (Throwable e) {
					Toast.makeText(context, R.string.boot_failed, Toast.LENGTH_SHORT).show();
				}
			}
			boolean dynatweak_service = MainActivity.properties.getProperty("dynatweak_service", "disabled").equals("enabled");
			if (dynatweak_service) {
				context.startService(new Intent(context, DynatweakService.class));
			}
		} catch (Throwable ex) {
			Toast.makeText(context, R.string.boot_exception, Toast.LENGTH_SHORT).show();
		}
	}
}
