package org.hexian000.dynatweak;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.hexian000.dynatweak.DynatweakApp.LOG_TAG;

/**
 * Created by hexian on 2017/6/18.
 * Boot time tweaks
 */
public class BootReceiver extends BroadcastReceiver {

	static final int PROFILE_BALANCED = 2;
	static final int PROFILE_DISABLED = 0;
	static final int PROFILE_POWERSAVE = 1;
	static final int PROFILE_PERFORMANCE = 3;
	static final int PROFILE_GAMING = 4;
	static final int HOTPLUG_ALLCORES = 0;
	static final int HOTPLUG_LITTLECORES = 1;
	static final int HOTPLUG_DRIVER = 2;

	static synchronized void tweak(int hotplug, int profile) throws IOException {
		Log.d(LOG_TAG, "Start tweaking...");
		Kernel k = Kernel.getInstance();
		Kernel.CpuCore cpu0 = k.cpuCores.get(0);

		// CPU Hotplug
		k.setNode("/proc/hps/enabled", "0");
		k.setNode("/sys/module/blu_plug/parameters/enabled", "0");
		k.setNode("/sys/module/autosmp/parameters/enabled", "N");
		k.setNode("/sys/kernel/alucard_hotplug/hotplug_enable", "0");
		k.setNode("/sys/kernel/msm_mpdecision/conf/enabled", "0");
		k.setNode("/sys/module/msm_hotplug/msm_enabled", "0");

		// Thermal
		if (k.hasCoreControl()) {
			k.setCoreControlMask(0);
		}

		k.commit(); // Hotplug & thermal tweaks should be committed before CPU tweaking

		// Entropy
		k.setSysctl("kernel.random.read_wakeup_threshold", "128");
		k.setSysctl("kernel.random.write_wakeup_threshold", "512");

		// VM
		k.setSysctl("vm.dirty_expire_centisecs", "2000");
		k.setSysctl("vm.dirty_writeback_centisecs", "1000");

		// Misc
		k.setNode("/sys/kernel/fast_charge/force_fast_charge", "1");
		k.setNode("/sys/kernel/sched/arch_power", "1");
		k.setNode("/sys/module/workqueue/parameters/power_efficent", "Y");

		// IO
		List<String> block = k.listBlockDevices();
		for (String i : block) {
			Log.i(LOG_TAG, "block device detected: " + i);
			if (k.hasNode(i + "/queue/scheduler")) {
				k.setNode(i + "/queue/iostats", "0");
				k.setNode(i + "/queue/add_random", "0");
				k.setNode(i + "/queue/read_ahead_kb", "1024");
				k.setNode(i + "/queue/rq_affinity", "1");
				k.setNode(i + "/queue/rotational", "0");
				k.setNode(i + "/queue/nr_requests", "128");
				List<String> schedulers = k.listBlockAvailableScheduler(i + "/queue/scheduler");
				String scheduler = null;
				if (schedulers.contains("maple")) {
					scheduler = "maple";
				} else if (schedulers.contains("fiops")) {
					scheduler = "fiops";
				} else if (schedulers.contains("sioplus")) {
					scheduler = "sioplus";
				} else if (schedulers.contains("sio")) {
					scheduler = "sio";
				} else if (schedulers.contains("zen")) {
					scheduler = "zen";
				} else if (schedulers.contains("bfq")) {
					scheduler = "bfq";
				} else if (schedulers.contains("cfq")) {
					scheduler = "cfq";
				} else if (schedulers.contains("deadline")) {
					scheduler = "deadline";
				} else if (schedulers.contains("noop")) {
					scheduler = "noop";
				}
				if (scheduler != null) {
					k.setNode(i + "/queue/scheduler", scheduler);
				}
			}
		}

		List<Kernel.ClusterPolicy> allPolicy = k.getAllPolicies();
		List<String> allGovernors = cpu0.getScalingAvailableGovernors();
		List<String> governor = new ArrayList<>();
		List<Integer> profiles = new ArrayList<>();
		final boolean multiCluster = allPolicy.size() > 1;

		if (!multiCluster) { // 单簇处理器
			Log.d(LOG_TAG, "single-cluster profile=" + profile);
			switch (profile) {
			case PROFILE_DISABLED:
				break;
			case PROFILE_POWERSAVE: {
				final String[] preferList = {"zzmoove"};
				governor.add(preferGovernor(allGovernors, preferList));
				break;
			}
			case PROFILE_BALANCED: {
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
			Log.d(LOG_TAG, "multi-cluster profile=" + profile);
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
				case PROFILE_BALANCED: {
					final String[] preferList = {"blu_active", "zzmoove"};
					governor.add(preferGovernor(allGovernors, preferList));
					if (i == 0) {
						profiles.add(PROFILE_BALANCED);
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
						profiles.add(PROFILE_BALANCED);
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
					cpu.trySetOnline(true);
					if (profile == PROFILE_GAMING) {
						cpu.setScalingMinFrequency(cpu.getMaxFrequency());
					} else {
						cpu.setScalingMinFrequency(cpu.getMinFrequency());
					}
					cpu.setScalingMaxFrequency(cpu.getMaxFrequency());
					// Per cpu governor tweak
					if (profile != PROFILE_DISABLED) {
						cpu.trySetGovernor(governor.get(cpu.getCluster()));
						Log.d(LOG_TAG, "tweaking cpu " + cpu.getId() +
								": governor=" + governor.get(cpu.getCluster()) +
								", profile=" + profiles.get(cpu.getCluster()) +
								", path=" + cpu.getPath() + "/cpufreq");
						tweakGovernor(k, cpu, cpu.getPath() + "/cpufreq",
								governor.get(cpu.getCluster()), profiles.get(cpu.getCluster()));
					}
					break;
				} catch (Throwable ex) {
					Log.w(LOG_TAG, "cpu tweaking error", ex);
				}
			}
		}

		// Per cluster governor tweak
		for (int i = 0; i < allPolicy.size(); i++) {
			Kernel.ClusterPolicy clusterPolicy = allPolicy.get(i);
			if (profile != PROFILE_DISABLED) {
				Log.d(LOG_TAG, "tweaking cluster " + i +
						": governor=" + governor.get(i) + ", profile=" + profiles.get(i));
			}
			Kernel.CpuCore cpu = k.cpuCores.get(clusterPolicy.getStartCpu());
			// Qualcomm core control
			if (k.hasNode(cpu.getPath() + "/core_ctl")) {
				Log.i(LOG_TAG, "policy" + i + ": core_ctl detected");
				k.setNode(cpu.getPath() + "/core_ctl/max_cpus", clusterPolicy.getCpuCount() + "");
				k.setNode(cpu.getPath() + "/core_ctl/offline_delay_ms", "100");
				if (!k.readNode(cpu.getPath() + "/core_ctl/is_big_cluster").equals("0")) {
					switch (profile) { // big cluster
					case PROFILE_POWERSAVE:
						k.setNode(cpu.getPath() + "/core_ctl/busy_down_thres",
								setAllCoresTheSame("40", clusterPolicy.getCpuCount()));
						k.setNode(cpu.getPath() + "/core_ctl/busy_up_thres",
								setAllCoresTheSame("90", clusterPolicy.getCpuCount()));
						k.setNode(cpu.getPath() + "/core_ctl/min_cpus", "0");
						break;
					case PROFILE_BALANCED:
						k.setNode(cpu.getPath() + "/core_ctl/busy_down_thres",
								setAllCoresTheSame("40", clusterPolicy.getCpuCount()));
						k.setNode(cpu.getPath() + "/core_ctl/busy_up_thres",
								setAllCoresTheSame("60", clusterPolicy.getCpuCount()));
						k.setNode(cpu.getPath() + "/core_ctl/min_cpus", "0");
						break;
					case PROFILE_PERFORMANCE:
						k.setNode(cpu.getPath() + "/core_ctl/busy_down_thres",
								setAllCoresTheSame("30", clusterPolicy.getCpuCount()));
						k.setNode(cpu.getPath() + "/core_ctl/busy_up_thres",
								setAllCoresTheSame("60", clusterPolicy.getCpuCount()));
						k.setNode(cpu.getPath() + "/core_ctl/min_cpus", Math.min(clusterPolicy.getCpuCount(), 2) + "");
						break;
					case PROFILE_GAMING:
						k.setNode(cpu.getPath() + "/core_ctl/busy_down_thres",
								setAllCoresTheSame("0", clusterPolicy.getCpuCount()));
						k.setNode(cpu.getPath() + "/core_ctl/busy_up_thres",
								setAllCoresTheSame("0", clusterPolicy.getCpuCount()));
						k.setNode(cpu.getPath() + "/core_ctl/min_cpus", clusterPolicy.getCpuCount() + "");
						break;
					}
				} else { // little cluster
					switch (profile) {
					case PROFILE_DISABLED:
						break;
					case PROFILE_POWERSAVE:
						k.setNode(cpu.getPath() + "/core_ctl/busy_down_thres",
								setAllCoresTheSame("10", clusterPolicy.getCpuCount()));
						k.setNode(cpu.getPath() + "/core_ctl/busy_up_thres",
								setAllCoresTheSame("30", clusterPolicy.getCpuCount()));
						k.setNode(cpu.getPath() + "/core_ctl/min_cpus", Math.min(clusterPolicy.getCpuCount(), 2) + "");
						break;
					case PROFILE_BALANCED:
					case PROFILE_PERFORMANCE:
					case PROFILE_GAMING:
						k.setNode(cpu.getPath() + "/core_ctl/busy_down_thres",
								setAllCoresTheSame("0", clusterPolicy.getCpuCount()));
						k.setNode(cpu.getPath() + "/core_ctl/busy_up_thres",
								setAllCoresTheSame("0", clusterPolicy.getCpuCount()));
						k.setNode(cpu.getPath() + "/core_ctl/min_cpus", clusterPolicy.getCpuCount() + "");
						break;
					}
				}
			}
			// Per policy
			if (profile != PROFILE_DISABLED) {
				String policy = clusterPolicy.getPolicyPath();
				if (policy != null) {
					tweakGovernor(k, cpu, policy, governor.get(i), profiles.get(i));
				}
			}
		}

		// CPU big.LITTLE
		switch (profile) {
		case PROFILE_DISABLED:
			break;
		case PROFILE_POWERSAVE:
			k.setSysctl("kernel.sched_downmigrate", "100");
			k.setSysctl("kernel.sched_upmigrate", "99");
			k.setSysctl("kernel.sched_spill_nr_run", "10");
			k.setSysctl("kernel.sched_spill_load", "100");
			break;
		case PROFILE_BALANCED:
			k.setSysctl("kernel.sched_downmigrate", "70");
			k.setSysctl("kernel.sched_upmigrate", "90");
			k.setSysctl("kernel.sched_spill_nr_run", "4");
			k.setSysctl("kernel.sched_spill_load", "99");
			break;
		case PROFILE_PERFORMANCE:
			k.setSysctl("kernel.sched_downmigrate", "60");
			k.setSysctl("kernel.sched_upmigrate", "80");
			k.setSysctl("kernel.sched_spill_nr_run", "2");
			k.setSysctl("kernel.sched_spill_load", "90");
			break;
		case PROFILE_GAMING:
			k.setSysctl("kernel.sched_downmigrate", "60");
			k.setSysctl("kernel.sched_upmigrate", "80");
			k.setSysctl("kernel.sched_spill_nr_run", "2");
			k.setSysctl("kernel.sched_spill_load", "90");
			break;
		}

		// MSM Performance
		if (k.hasNode("/sys/module/msm_performance/parameters")) {
			Log.i(LOG_TAG, "msm_performance detected");
			final String msm_performance = "/sys/module/msm_performance/parameters/";
			StringBuilder cpu_max_freq = new StringBuilder();
			StringBuilder cpu_min_freq = new StringBuilder();
			for (Kernel.CpuCore cpu : k.cpuCores) {
				cpu_max_freq.append(cpu.getId()).append(':').append(cpu.getMaxFrequency()).append(' ');
				cpu_min_freq.append(cpu.getId()).append(':').append(cpu.getMinFrequency()).append(' ');
			}
			k.setNode(msm_performance + "cpu_max_freq", cpu_max_freq.toString());
			k.setNode(msm_performance + "cpu_min_freq", cpu_min_freq.toString());
			k.setNode(msm_performance + "touchboost", "0");
			k.setNode(msm_performance + "workload_detect", "1");
		}

		// CPU Boost
		k.setNode("/sys/module/cpu_boost/parameters/boost_ms", "40");
		k.setNode("/sys/module/cpu_boost/parameters/sync_threshold", cpu0.fitPercentage(0.3) + "");
		StringBuilder boostFreq = new StringBuilder(), boostFreq_s2 = new StringBuilder();
		for (Kernel.CpuCore cpu : k.cpuCores) {
			if (multiCluster) {
				if (cpu.getCluster() == 0 && cpu.getId() < 2) {
					boostFreq.append(cpu.getId()).append(':').append(cpu.fitPercentage(0.5)).append(' ');
					boostFreq_s2.append(cpu.getId()).append(':').append(cpu.fitPercentage(0.3)).append(' ');
				} else {
					boostFreq.append(cpu.getId()).append(":0 ");
					boostFreq_s2.append(cpu.getId()).append(": ");
				}
			} else {
				if (cpu.getId() < 2) {
					boostFreq.append(cpu.getId()).append(':').append(cpu.fitPercentage(0.5)).append(' ');
					boostFreq_s2.append(cpu.getId()).append(":").append(cpu.fitPercentage(0.3)).append(' ');
				} else {
					boostFreq.append(cpu.getId()).append(":0 ");
					boostFreq_s2.append(cpu.getId()).append(":0 ");
				}
			}
		}
		k.setNode("/sys/module/cpu_boost/parameters/input_boost_freq", boostFreq.toString());
		k.setNode("/sys/module/cpu_boost/parameters/input_boost_freq_s2", boostFreq_s2.toString());
		k.setNode("/sys/module/cpu_boost/parameters/input_boost_ms", "80");
		k.setNode("/sys/module/cpu_boost/parameters/input_boost_ms_s2", "160");

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
					k.setNode(gpuNodeRoot + "/max_pwrlevel", (num_pwrlevels - 1) + "");
					k.setNode(gpuNodeRoot + "/min_pwrlevel", (num_pwrlevels - 1) + "");
				} catch (Throwable ignore) {
				}
			}
			break;
		case PROFILE_BALANCED: // governor controlled with idler
			// Adreno Idler
			k.setNode("/sys/module/adreno_idler/parameters/adreno_idler_active", "Y");
			k.setNode("/sys/module/adreno_idler/parameters/adreno_idler_downdifferential", "40");
			k.setNode("/sys/module/adreno_idler/parameters/adreno_idler_idleworkload", "8000");
			k.setNode("/sys/module/adreno_idler/parameters/adreno_idler_idlewait", "50");
			if (num_pwrlevels != null) {
				try {
					k.setNode(gpuNodeRoot + "/max_pwrlevel", "0");
					k.setNode(gpuNodeRoot + "/min_pwrlevel", (num_pwrlevels - 1) + "");
				} catch (Throwable ignore) {
				}
			}
			break;
		case PROFILE_PERFORMANCE: // governor controlled without idler
			k.setNode("/sys/module/adreno_idler/parameters/adreno_idler_active", "N");
			if (num_pwrlevels != null) {
				try {
					k.setNode(gpuNodeRoot + "/max_pwrlevel", "0");
					k.setNode(gpuNodeRoot + "/min_pwrlevel", (num_pwrlevels - 1) + "");
				} catch (Throwable ignore) {
				}
			}
			break;
		case PROFILE_GAMING: // maximize performance
			if (num_pwrlevels != null) {
				try {
					k.setNode(gpuNodeRoot + "/max_pwrlevel", "0");
					k.setNode(gpuNodeRoot + "/min_pwrlevel", "0");
				} catch (Throwable ignore) {
				}
			}
			break;
		}

		// hotplug
		switch (hotplug) {
		case HOTPLUG_ALLCORES: // all cores
			k.setNode("/sys/devices/system/cpu/sched_mc_power_savings", "0");
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
			k.setNode("/sys/devices/system/cpu/sched_mc_power_savings", "1");
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
				if (success) {
					break;
				}
			}
			if (!success) {
				k.runAsRoot("start mpdecision");
			}
			k.setNode("/sys/devices/system/cpu/sched_mc_power_savings", "2");
			break;
		}
		k.commit();

		Log.d(LOG_TAG, "Finished tweaking...");
	}

	private static String preferGovernor(List<String> allGovernors, String[] names) {
		for (String name : names) {
			if (allGovernors.contains(name)) {
				return name;
			}
		}
		return "interactive";
	}

	private static String setAllCoresTheSame(String value, int core) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < core; i++) {
			sb.append(value).append(' ');
		}
		return sb.toString();
	}

	private static void tweakGovernor(Kernel k, Kernel.CpuCore cpu, String policy, String governor, int profile) throws IOException {
		switch (profile) {
		case PROFILE_POWERSAVE: // powersave
			switch (governor) {
			case "interactive":
				k.setNode(policy + "/interactive/hispeed_freq", cpu.fitPercentage(0.5) + "");
				k.setNode(policy + "/interactive/sync_freq", cpu.fitPercentage(0) + "");
				k.setNode(policy + "/interactive/above_hispeed_delay", "100000");
				k.setNode(policy + "/interactive/go_hispeed_load", "99");
				k.setNode(policy + "/interactive/target_loads", "90");
				k.setNode(policy + "/interactive/io_is_busy", "0");
				k.setNode(policy + "/interactive/timer_rate", "40000");
				k.setNode(policy + "/interactive/timer_slack", "40000");
				k.setNode(policy + "/interactive/min_sample_time", "80000");
				k.setNode(policy + "/interactive/boostpulse_duration", "80000");
				k.setNode(policy + "/interactive/use_migration_notif", "1");
				k.setNode(policy + "/interactive/ignore_hispeed_on_notif", "1");
				k.setNode(policy + "/interactive/max_freq_hysteresis", "80000");
				break;
			case "blu_active":
				k.setNode(policy + "/blu_active/above_hispeed_delay",
						"20000 " + cpu.fitPercentage(0.5) + ":40000 " +
								cpu.fitPercentage(0.7) + ":20000 " +
								cpu.fitPercentage(0.85) + ":80000 " +
								cpu.fitPercentage(1) + ":100000");
				k.setNode(policy + "/blu_active/align_windows", "1");
				k.setNode(policy + "/blu_active/fastlane", "0");
				k.setNode(policy + "/blu_active/fastlane_threshold", "80");
				k.setNode(policy + "/blu_active/go_hispeed_load", "90");
				k.setNode(policy + "/blu_active/hispeed_freq", cpu.fitPercentage(0.5) + "");
				k.setNode(policy + "/blu_active/io_is_busy", "0");
				k.setNode(policy + "/blu_active/min_sample_time", "20000");
				k.setNode(policy + "/blu_active/target_loads",
						"85 " + cpu.fitPercentage(0.6) + ":90 " +
								cpu.fitPercentage(0.8) + ":70 " +
								cpu.fitPercentage(0.9) + ":95");
				k.setNode(policy + "/blu_active/timer_rate", "20000");
				k.setNode(policy + "/blu_active/timer_slack", "20000");
				break;
			case "zzmoove":
				k.setNode(policy + "/zzmoove/auto_adjust_freq_thresholds", "0");
				k.setNode(policy + "/zzmoove/disable_hotplug", "1");
				k.setNode(policy + "/zzmoove/disable_hotplug_sleep", "1");
				k.setNode(policy + "/zzmoove/down_threshold", "40");
				k.setNode(policy + "/zzmoove/down_threshold_hotplug1", "45");
				k.setNode(policy + "/zzmoove/down_threshold_hotplug2", "55");
				k.setNode(policy + "/zzmoove/down_threshold_hotplug3", "65");
				k.setNode(policy + "/zzmoove/down_threshold_hotplug4", "55");
				k.setNode(policy + "/zzmoove/down_threshold_hotplug5", "55");
				k.setNode(policy + "/zzmoove/down_threshold_hotplug6", "55");
				k.setNode(policy + "/zzmoove/down_threshold_hotplug7", "55");
				k.setNode(policy + "/zzmoove/down_threshold_hotplug_freq1", cpu.fitFrequency(652800) + "");
				k.setNode(policy + "/zzmoove/down_threshold_hotplug_freq2", cpu.fitFrequency(960000) + "");
				k.setNode(policy + "/zzmoove/down_threshold_hotplug_freq3", cpu.fitFrequency(1267200) + "");
				k.setNode(policy + "/zzmoove/down_threshold_hotplug_freq4", "0");
				k.setNode(policy + "/zzmoove/down_threshold_hotplug_freq5", "0");
				k.setNode(policy + "/zzmoove/down_threshold_hotplug_freq6", "0");
				k.setNode(policy + "/zzmoove/down_threshold_hotplug_freq7", "0");
				k.setNode(policy + "/zzmoove/down_threshold_sleep", "60");
				k.setNode(policy + "/zzmoove/early_demand", "0");
				k.setNode(policy + "/zzmoove/early_demand_sleep", "1");
				k.setNode(policy + "/zzmoove/fast_scaling_up", "0");
				k.setNode(policy + "/zzmoove/fast_scaling_down", "0");
				k.setNode(policy + "/zzmoove/fast_scaling_sleep_up", "0");
				k.setNode(policy + "/zzmoove/fast_scaling_sleep_down", "0");
				k.setNode(policy + "/zzmoove/afs_threshold1", "30");
				k.setNode(policy + "/zzmoove/afs_threshold2", "50");
				k.setNode(policy + "/zzmoove/afs_threshold3", "70");
				k.setNode(policy + "/zzmoove/afs_threshold4", "90");
				k.setNode(policy + "/zzmoove/freq_limit", "0");
				k.setNode(policy + "/zzmoove/freq_limit_sleep", cpu.fitFrequency(729600) + "");
				k.setNode(policy + "/zzmoove/grad_up_threshold", "50");
				k.setNode(policy + "/zzmoove/grad_up_threshold_sleep", "28");
				k.setNode(policy + "/zzmoove/hotplug_block_up_cycles", "2");
				k.setNode(policy + "/zzmoove/block_up_multiplier_hotplug1", "1");
				k.setNode(policy + "/zzmoove/block_up_multiplier_hotplug2", "1");
				k.setNode(policy + "/zzmoove/block_up_multiplier_hotplug3", "1");
				k.setNode(policy + "/zzmoove/block_up_multiplier_hotplug4", "1");
				k.setNode(policy + "/zzmoove/block_up_multiplier_hotplug5", "1");
				k.setNode(policy + "/zzmoove/block_up_multiplier_hotplug6", "1");
				k.setNode(policy + "/zzmoove/block_up_multiplier_hotplug7", "1");
				k.setNode(policy + "/zzmoove/hotplug_block_down_cycles", "20");
				k.setNode(policy + "/zzmoove/block_down_multiplier_hotplug1", "1");
				k.setNode(policy + "/zzmoove/block_down_multiplier_hotplug2", "1");
				k.setNode(policy + "/zzmoove/block_down_multiplier_hotplug3", "1");
				k.setNode(policy + "/zzmoove/block_down_multiplier_hotplug4", "1");
				k.setNode(policy + "/zzmoove/block_down_multiplier_hotplug5", "1");
				k.setNode(policy + "/zzmoove/block_down_multiplier_hotplug6", "1");
				k.setNode(policy + "/zzmoove/block_down_multiplier_hotplug7", "1");
				k.setNode(policy + "/zzmoove/hotplug_stagger_up", "0");
				k.setNode(policy + "/zzmoove/hotplug_stagger_down", "0");
				k.setNode(policy + "/zzmoove/hotplug_idle_threshold", "0");
				k.setNode(policy + "/zzmoove/hotplug_idle_freq", "0");
				k.setNode(policy + "/zzmoove/hotplug_sleep", "1");
				k.setNode(policy + "/zzmoove/hotplug_engage_freq", "0");
				k.setNode(policy + "/zzmoove/hotplug_max_limit", "0");
				k.setNode(policy + "/zzmoove/hotplug_min_limit", "0");
				k.setNode(policy + "/zzmoove/hotplug_lock", "0");
				k.setNode(policy + "/zzmoove/ignore_nice_load", "0");
				k.setNode(policy + "/zzmoove/sampling_down_factor", "1");
				k.setNode(policy + "/zzmoove/sampling_down_max_momentum", "0");
				k.setNode(policy + "/zzmoove/sampling_down_momentum_sensitivity", "50");
				k.setNode(policy + "/zzmoove/sampling_rate", "100000");
				k.setNode(policy + "/zzmoove/sampling_rate_idle", "180000");
				k.setNode(policy + "/zzmoove/sampling_rate_idle_delay", "0");
				k.setNode(policy + "/zzmoove/sampling_rate_idle_threshold", "40");
				k.setNode(policy + "/zzmoove/sampling_rate_sleep_multiplier", "4");
				k.setNode(policy + "/zzmoove/scaling_block_cycles", "0");
				k.setNode(policy + "/zzmoove/scaling_block_temp", "0");
				k.setNode(policy + "/zzmoove/scaling_trip_temp", "0");
				k.setNode(policy + "/zzmoove/scaling_block_freq", "0");
				k.setNode(policy + "/zzmoove/scaling_block_threshold", "0");
				k.setNode(policy + "/zzmoove/scaling_block_force_down", "2");
				k.setNode(policy + "/zzmoove/scaling_fastdown_freq", cpu.fitFrequency(1958400) + "");
				k.setNode(policy + "/zzmoove/scaling_fastdown_up_threshold", "95");
				k.setNode(policy + "/zzmoove/scaling_fastdown_down_threshold", "90");
				k.setNode(policy + "/zzmoove/scaling_responsiveness_freq", cpu.fitFrequency(652800) + "");
				k.setNode(policy + "/zzmoove/scaling_responsiveness_up_threshold", "20");
				k.setNode(policy + "/zzmoove/scaling_proportional", "1");
				k.setNode(policy + "/zzmoove/inputboost_cycles", "0");
				k.setNode(policy + "/zzmoove/inputboost_up_threshold", "80");
				k.setNode(policy + "/zzmoove/inputboost_punch_cycles", "20");
				k.setNode(policy + "/zzmoove/inputboost_punch_freq", cpu.fitFrequency(1728000) + "");
				k.setNode(policy + "/zzmoove/inputboost_punch_on_fingerdown", "1");
				k.setNode(policy + "/zzmoove/inputboost_punch_on_fingermove", "0");
				k.setNode(policy + "/zzmoove/inputboost_punch_on_epenmove", "0");
				k.setNode(policy + "/zzmoove/inputboost_typingbooster_up_threshold", "40");
				k.setNode(policy + "/zzmoove/inputboost_typingbooster_cores", "3");
				k.setNode(policy + "/zzmoove/music_max_freq", cpu.fitFrequency(1497600) + "");
				k.setNode(policy + "/zzmoove/music_min_freq", cpu.fitFrequency(422400) + "");
				k.setNode(policy + "/zzmoove/music_min_cores", "2");
				k.setNode(policy + "/zzmoove/smooth_up", "75");
				k.setNode(policy + "/zzmoove/smooth_up_sleep", "100");
				k.setNode(policy + "/zzmoove/up_threshold", "95");
				k.setNode(policy + "/zzmoove/up_threshold_hotplug1", "60");
				k.setNode(policy + "/zzmoove/up_threshold_hotplug2", "80");
				k.setNode(policy + "/zzmoove/up_threshold_hotplug3", "98");
				k.setNode(policy + "/zzmoove/up_threshold_hotplug4", "68");
				k.setNode(policy + "/zzmoove/up_threshold_hotplug5", "68");
				k.setNode(policy + "/zzmoove/up_threshold_hotplug6", "68");
				k.setNode(policy + "/zzmoove/up_threshold_hotplug7", "68");
				k.setNode(policy + "/zzmoove/up_threshold_hotplug_freq1", cpu.fitFrequency(729600) + "");
				k.setNode(policy + "/zzmoove/up_threshold_hotplug_freq2", cpu.fitFrequency(1190400) + "");
				k.setNode(policy + "/zzmoove/up_threshold_hotplug_freq3", cpu.fitFrequency(1574400) + "");
				k.setNode(policy + "/zzmoove/up_threshold_hotplug_freq4", "0");
				k.setNode(policy + "/zzmoove/up_threshold_hotplug_freq5", "0");
				k.setNode(policy + "/zzmoove/up_threshold_hotplug_freq6", "0");
				k.setNode(policy + "/zzmoove/up_threshold_hotplug_freq7", "0");
				k.setNode(policy + "/zzmoove/up_threshold_sleep", "100");
				break;
			case "ondemand":
				// should never happen
				break;
			}
			break;
		case PROFILE_BALANCED: // balanced
			switch (governor) {
			case "interactive":
				k.setNode(policy + "/interactive/hispeed_freq", cpu.fitPercentage(0.8) + "");
				k.setNode(policy + "/interactive/sync_freq", cpu.fitPercentage(0.4) + "");
				k.setNode(policy + "/interactive/up_threshold_any_cpu_freq", cpu.fitPercentage(0.9) + "");
				k.setNode(policy + "/interactive/above_hispeed_delay",
						"20000 " + cpu.fitPercentage(0.85) + ":80000");
				k.setNode(policy + "/interactive/go_hispeed_load", "95");
				k.setNode(policy + "/interactive/target_loads",
						"80 " + cpu.fitFrequency(1000000) + ":90 ");
				k.setNode(policy + "/interactive/up_threshold_any_cpu_load", "80");
				k.setNode(policy + "/interactive/io_is_busy", "0");
				k.setNode(policy + "/interactive/timer_rate", "20000");
				k.setNode(policy + "/interactive/timer_slack", "20000");
				k.setNode(policy + "/interactive/min_sample_time", "80000");
				k.setNode(policy + "/interactive/boostpulse_duration", "80000");
				k.setNode(policy + "/interactive/use_migration_notif", "1");
				k.setNode(policy + "/interactive/ignore_hispeed_on_notif", "1");
				k.setNode(policy + "/interactive/max_freq_hysteresis", "80000");
				break;
			case "blu_active":
				k.setNode(policy + "/blu_active/above_hispeed_delay",
						"20000 " + cpu.fitPercentage(0.85) + ":80000");
				k.setNode(policy + "/blu_active/align_windows", "1");
				k.setNode(policy + "/blu_active/fastlane", "1");
				k.setNode(policy + "/blu_active/fastlane_threshold", "80");
				k.setNode(policy + "/blu_active/go_hispeed_load", "90");
				k.setNode(policy + "/blu_active/hispeed_freq", cpu.fitPercentage(0.5) + "");
				k.setNode(policy + "/blu_active/io_is_busy", "0");
				k.setNode(policy + "/blu_active/min_sample_time", "20000");
				k.setNode(policy + "/blu_active/target_loads",
						"80 " + cpu.fitFrequency(1000000) + ":90 ");
				k.setNode(policy + "/blu_active/timer_rate", "20000");
				k.setNode(policy + "/blu_active/timer_slack", "20000");
				break;
			case "zzmoove":
				k.setNode(policy + "/zzmoove/auto_adjust_freq_thresholds", "0");
				k.setNode(policy + "/zzmoove/disable_hotplug", "1");
				k.setNode(policy + "/zzmoove/disable_hotplug_sleep", "1");
				k.setNode(policy + "/zzmoove/down_threshold", "52");
				k.setNode(policy + "/zzmoove/down_threshold_hotplug1", "55");
				k.setNode(policy + "/zzmoove/down_threshold_hotplug2", "55");
				k.setNode(policy + "/zzmoove/down_threshold_hotplug3", "55");
				k.setNode(policy + "/zzmoove/down_threshold_hotplug4", "55");
				k.setNode(policy + "/zzmoove/down_threshold_hotplug5", "55");
				k.setNode(policy + "/zzmoove/down_threshold_hotplug6", "55");
				k.setNode(policy + "/zzmoove/down_threshold_hotplug7", "55");
				k.setNode(policy + "/zzmoove/down_threshold_hotplug_freq1", "0");
				k.setNode(policy + "/zzmoove/down_threshold_hotplug_freq2", "0");
				k.setNode(policy + "/zzmoove/down_threshold_hotplug_freq3", "0");
				k.setNode(policy + "/zzmoove/down_threshold_hotplug_freq4", "0");
				k.setNode(policy + "/zzmoove/down_threshold_hotplug_freq5", "0");
				k.setNode(policy + "/zzmoove/down_threshold_hotplug_freq6", "0");
				k.setNode(policy + "/zzmoove/down_threshold_hotplug_freq7", "0");
				k.setNode(policy + "/zzmoove/down_threshold_sleep", "44");
				k.setNode(policy + "/zzmoove/early_demand", "0");
				k.setNode(policy + "/zzmoove/early_demand_sleep", "1");
				k.setNode(policy + "/zzmoove/fast_scaling_up", "0");
				k.setNode(policy + "/zzmoove/fast_scaling_down", "0");
				k.setNode(policy + "/zzmoove/fast_scaling_sleep_up", "0");
				k.setNode(policy + "/zzmoove/fast_scaling_sleep_down", "0");
				k.setNode(policy + "/zzmoove/autofastscalingstepone", "25");
				k.setNode(policy + "/zzmoove/autofastscalingsteptwo", "50");
				k.setNode(policy + "/zzmoove/autofastscalingstepthree", "75");
				k.setNode(policy + "/zzmoove/autofastscalingstepfour", "90");
				k.setNode(policy + "/zzmoove/freq_limit", "0");
				k.setNode(policy + "/zzmoove/freq_limit_sleep", "0");
				k.setNode(policy + "/zzmoove/grad_up_threshold", "25");
				k.setNode(policy + "/zzmoove/grad_up_threshold_sleep", "28");
				k.setNode(policy + "/zzmoove/hotplug_block_up_cycles", "2");
				k.setNode(policy + "/zzmoove/block_up_multiplier_hotplug1", "1");
				k.setNode(policy + "/zzmoove/block_up_multiplier_hotplug2", "1");
				k.setNode(policy + "/zzmoove/block_up_multiplier_hotplug3", "1");
				k.setNode(policy + "/zzmoove/block_up_multiplier_hotplug4", "1");
				k.setNode(policy + "/zzmoove/block_up_multiplier_hotplug5", "1");
				k.setNode(policy + "/zzmoove/block_up_multiplier_hotplug6", "1");
				k.setNode(policy + "/zzmoove/block_up_multiplier_hotplug7", "1");
				k.setNode(policy + "/zzmoove/hotplug_block_down_cycles", "20");
				k.setNode(policy + "/zzmoove/block_down_multiplier_hotplug1", "1");
				k.setNode(policy + "/zzmoove/block_down_multiplier_hotplug2", "1");
				k.setNode(policy + "/zzmoove/block_down_multiplier_hotplug3", "1");
				k.setNode(policy + "/zzmoove/block_down_multiplier_hotplug4", "1");
				k.setNode(policy + "/zzmoove/block_down_multiplier_hotplug5", "1");
				k.setNode(policy + "/zzmoove/block_down_multiplier_hotplug6", "1");
				k.setNode(policy + "/zzmoove/block_down_multiplier_hotplug7", "1");
				k.setNode(policy + "/zzmoove/hotplug_stagger_up", "0");
				k.setNode(policy + "/zzmoove/hotplug_stagger_down", "0");
				k.setNode(policy + "/zzmoove/hotplug_idle_threshold", "0");
				k.setNode(policy + "/zzmoove/hotplug_idle_freq", "0");
				k.setNode(policy + "/zzmoove/hotplug_sleep", "0");
				k.setNode(policy + "/zzmoove/hotplug_engage_freq", "0");
				k.setNode(policy + "/zzmoove/hotplug_max_limit", "0");
				k.setNode(policy + "/zzmoove/hotplug_min_limit", "0");
				k.setNode(policy + "/zzmoove/hotplug_lock", "0");
				k.setNode(policy + "/zzmoove/ignore_nice_load", "0");
				k.setNode(policy + "/zzmoove/sampling_down_factor", "1");
				k.setNode(policy + "/zzmoove/sampling_down_max_momentum", "0");
				k.setNode(policy + "/zzmoove/sampling_down_momentum_sensitivity", "50");
				k.setNode(policy + "/zzmoove/sampling_rate", "100000");
				k.setNode(policy + "/zzmoove/sampling_rate_idle", "180000");
				k.setNode(policy + "/zzmoove/sampling_rate_idle_delay", "0");
				k.setNode(policy + "/zzmoove/sampling_rate_idle_threshold", "40");
				k.setNode(policy + "/zzmoove/sampling_rate_sleep_multiplier", "2");
				k.setNode(policy + "/zzmoove/scaling_block_cycles", "0");
				k.setNode(policy + "/zzmoove/scaling_block_temp", "0");
				k.setNode(policy + "/zzmoove/scaling_trip_temp", "0");
				k.setNode(policy + "/zzmoove/scaling_block_freq", cpu.fitFrequency(1728000) + "");
				k.setNode(policy + "/zzmoove/scaling_block_threshold", "10");
				k.setNode(policy + "/zzmoove/scaling_block_force_down", "2");
				k.setNode(policy + "/zzmoove/scaling_fastdown_freq", "0");
				k.setNode(policy + "/zzmoove/scaling_fastdown_up_threshold", "95");
				k.setNode(policy + "/zzmoove/scaling_fastdown_down_threshold", "90");
				k.setNode(policy + "/zzmoove/scaling_responsiveness_freq", "0");
				k.setNode(policy + "/zzmoove/scaling_responsiveness_up_threshold", "30");
				k.setNode(policy + "/zzmoove/scaling_proportional", "0");
				k.setNode(policy + "/zzmoove/inputboost_cycles", "0");
				k.setNode(policy + "/zzmoove/inputboost_up_threshold", "80");
				k.setNode(policy + "/zzmoove/inputboost_punch_cycles", "20");
				k.setNode(policy + "/zzmoove/inputboost_punch_freq", cpu.fitFrequency(1728000) + "");
				k.setNode(policy + "/zzmoove/inputboost_punch_on_fingerdown", "1");
				k.setNode(policy + "/zzmoove/inputboost_punch_on_fingermove", "0");
				k.setNode(policy + "/zzmoove/inputboost_punch_on_epenmove", "0");
				k.setNode(policy + "/zzmoove/inputboost_typingbooster_up_threshold", "40");
				k.setNode(policy + "/zzmoove/inputboost_typingbooster_cores", "3");
				k.setNode(policy + "/zzmoove/music_max_freq", cpu.fitFrequency(1497600) + "");
				k.setNode(policy + "/zzmoove/music_min_freq", cpu.fitFrequency(422400) + "");
				k.setNode(policy + "/zzmoove/music_min_cores", "2");
				k.setNode(policy + "/zzmoove/smooth_up", "75");
				k.setNode(policy + "/zzmoove/smooth_up_sleep", "100");
				k.setNode(policy + "/zzmoove/up_threshold", "70");
				k.setNode(policy + "/zzmoove/up_threshold_hotplug1", "68");
				k.setNode(policy + "/zzmoove/up_threshold_hotplug2", "68");
				k.setNode(policy + "/zzmoove/up_threshold_hotplug3", "68");
				k.setNode(policy + "/zzmoove/up_threshold_hotplug4", "68");
				k.setNode(policy + "/zzmoove/up_threshold_hotplug5", "68");
				k.setNode(policy + "/zzmoove/up_threshold_hotplug6", "68");
				k.setNode(policy + "/zzmoove/up_threshold_hotplug7", "68");
				k.setNode(policy + "/zzmoove/up_threshold_hotplug_freq1", "0");
				k.setNode(policy + "/zzmoove/up_threshold_hotplug_freq2", "0");
				k.setNode(policy + "/zzmoove/up_threshold_hotplug_freq3", "0");
				k.setNode(policy + "/zzmoove/up_threshold_hotplug_freq4", "0");
				k.setNode(policy + "/zzmoove/up_threshold_hotplug_freq5", "0");
				k.setNode(policy + "/zzmoove/up_threshold_hotplug_freq6", "0");
				k.setNode(policy + "/zzmoove/up_threshold_hotplug_freq7", "0");
				k.setNode(policy + "/zzmoove/up_threshold_sleep", "90");
				break;
			case "ondemand":
				// should never happen
				break;
			}
			break;
		case PROFILE_PERFORMANCE: // performance
			switch (governor) {
			case "interactive":
				k.setNode(policy + "/interactive/above_hispeed_delay",
						"20000 " + cpu.fitPercentage(0.85) + ":80000");
				k.setNode(policy + "/interactive/hispeed_freq", cpu.fitPercentage(0.6) + "");
				k.setNode(policy + "/interactive/sync_freq", cpu.fitPercentage(0.4) + "");
				k.setNode(policy + "/interactive/up_threshold_any_cpu_freq", cpu.fitPercentage(0.8) + "");
				k.setNode(policy + "/interactive/go_hispeed_load", "80");
				k.setNode(policy + "/interactive/target_loads",
						"60 " + cpu.fitFrequency(1000000) + ":80 ");
				k.setNode(policy + "/interactive/up_threshold_any_cpu_load", "70");
				k.setNode(policy + "/interactive/io_is_busy", "1");
				k.setNode(policy + "/interactive/timer_rate", "20000");
				k.setNode(policy + "/interactive/timer_slack", "20000");
				k.setNode(policy + "/interactive/min_sample_time", "80000");
				k.setNode(policy + "/interactive/boostpulse_duration", "80000");
				k.setNode(policy + "/interactive/use_migration_notif", "1");
				k.setNode(policy + "/interactive/ignore_hispeed_on_notif", "1");
				k.setNode(policy + "/interactive/max_freq_hysteresis", "40000");
				break;
			case "blu_active":
				k.setNode(policy + "/interactive/above_hispeed_delay",
						"20000 " + cpu.fitPercentage(0.85) + ":80000");
				k.setNode(policy + "/blu_active/align_windows", "1");
				k.setNode(policy + "/blu_active/fastlane", "1");
				k.setNode(policy + "/blu_active/fastlane_threshold", "25");
				k.setNode(policy + "/blu_active/go_hispeed_load", "80");
				k.setNode(policy + "/blu_active/hispeed_freq", cpu.fitPercentage(0.6) + "");
				k.setNode(policy + "/blu_active/io_is_busy", "0");
				k.setNode(policy + "/blu_active/min_sample_time", "20000");
				k.setNode(policy + "/blu_active/target_loads",
						"60 " + cpu.fitFrequency(1000000) + ":80 ");
				k.setNode(policy + "/blu_active/timer_rate", "20000");
				k.setNode(policy + "/blu_active/timer_slack", "20000");
				break;
			case "zzmoove":
				k.setNode(policy + "/zzmoove/auto_adjust_freq_thresholds", "0");
				k.setNode(policy + "/zzmoove/disable_hotplug", "1");
				k.setNode(policy + "/zzmoove/disable_hotplug_sleep", "1");
				k.setNode(policy + "/zzmoove/down_threshold", "20");
				k.setNode(policy + "/zzmoove/down_threshold_hotplug1", "25");
				k.setNode(policy + "/zzmoove/down_threshold_hotplug2", "35");
				k.setNode(policy + "/zzmoove/down_threshold_hotplug3", "45");
				k.setNode(policy + "/zzmoove/down_threshold_hotplug4", "55");
				k.setNode(policy + "/zzmoove/down_threshold_hotplug5", "55");
				k.setNode(policy + "/zzmoove/down_threshold_hotplug6", "55");
				k.setNode(policy + "/zzmoove/down_threshold_hotplug7", "55");
				k.setNode(policy + "/zzmoove/down_threshold_hotplug_freq1", cpu.fitFrequency(300000) + "");
				k.setNode(policy + "/zzmoove/down_threshold_hotplug_freq2", cpu.fitFrequency(1190400) + "");
				k.setNode(policy + "/zzmoove/down_threshold_hotplug_freq3", cpu.fitFrequency(1574400) + "");
				k.setNode(policy + "/zzmoove/down_threshold_hotplug_freq4", "0");
				k.setNode(policy + "/zzmoove/down_threshold_hotplug_freq5", "0");
				k.setNode(policy + "/zzmoove/down_threshold_hotplug_freq6", "0");
				k.setNode(policy + "/zzmoove/down_threshold_hotplug_freq7", "0");
				k.setNode(policy + "/zzmoove/down_threshold_sleep", "60");
				k.setNode(policy + "/zzmoove/early_demand", "1");
				k.setNode(policy + "/zzmoove/early_demand_sleep", "1");
				k.setNode(policy + "/zzmoove/fast_scaling_up", "1");
				k.setNode(policy + "/zzmoove/fast_scaling_down", "1");
				k.setNode(policy + "/zzmoove/fast_scaling_sleep_up", "2");
				k.setNode(policy + "/zzmoove/fast_scaling_sleep_down", "0");
				k.setNode(policy + "/zzmoove/afs_threshold1", "30");
				k.setNode(policy + "/zzmoove/afs_threshold2", "50");
				k.setNode(policy + "/zzmoove/afs_threshold3", "70");
				k.setNode(policy + "/zzmoove/afs_threshold4", "90");
				k.setNode(policy + "/zzmoove/freq_limit", "0");
				k.setNode(policy + "/zzmoove/freq_limit_sleep", cpu.fitFrequency(729600) + "");
				k.setNode(policy + "/zzmoove/grad_up_threshold", "25");
				k.setNode(policy + "/zzmoove/grad_up_threshold_sleep", "28");
				k.setNode(policy + "/zzmoove/hotplug_block_up_cycles", "2");
				k.setNode(policy + "/zzmoove/block_up_multiplier_hotplug1", "1");
				k.setNode(policy + "/zzmoove/block_up_multiplier_hotplug2", "1");
				k.setNode(policy + "/zzmoove/block_up_multiplier_hotplug3", "1");
				k.setNode(policy + "/zzmoove/block_up_multiplier_hotplug4", "1");
				k.setNode(policy + "/zzmoove/block_up_multiplier_hotplug5", "1");
				k.setNode(policy + "/zzmoove/block_up_multiplier_hotplug6", "1");
				k.setNode(policy + "/zzmoove/block_up_multiplier_hotplug7", "1");
				k.setNode(policy + "/zzmoove/hotplug_block_down_cycles", "20");
				k.setNode(policy + "/zzmoove/block_down_multiplier_hotplug1", "1");
				k.setNode(policy + "/zzmoove/block_down_multiplier_hotplug2", "1");
				k.setNode(policy + "/zzmoove/block_down_multiplier_hotplug3", "1");
				k.setNode(policy + "/zzmoove/block_down_multiplier_hotplug4", "1");
				k.setNode(policy + "/zzmoove/block_down_multiplier_hotplug5", "1");
				k.setNode(policy + "/zzmoove/block_down_multiplier_hotplug6", "1");
				k.setNode(policy + "/zzmoove/block_down_multiplier_hotplug7", "1");
				k.setNode(policy + "/zzmoove/hotplug_stagger_up", "0");
				k.setNode(policy + "/zzmoove/hotplug_stagger_down", "0");
				k.setNode(policy + "/zzmoove/hotplug_idle_threshold", "0");
				k.setNode(policy + "/zzmoove/hotplug_idle_freq", "0");
				k.setNode(policy + "/zzmoove/hotplug_sleep", "1");
				k.setNode(policy + "/zzmoove/hotplug_engage_freq", "0");
				k.setNode(policy + "/zzmoove/hotplug_max_limit", "0");
				k.setNode(policy + "/zzmoove/hotplug_min_limit", "0");
				k.setNode(policy + "/zzmoove/hotplug_lock", "0");
				k.setNode(policy + "/zzmoove/ignore_nice_load", "0");
				k.setNode(policy + "/zzmoove/sampling_down_factor", "4");
				k.setNode(policy + "/zzmoove/sampling_down_max_momentum", "50");
				k.setNode(policy + "/zzmoove/sampling_down_momentum_sensitivity", "25");
				k.setNode(policy + "/zzmoove/sampling_rate", "60000");
				k.setNode(policy + "/zzmoove/sampling_rate_idle", "100000");
				k.setNode(policy + "/zzmoove/sampling_rate_idle_delay", "0");
				k.setNode(policy + "/zzmoove/sampling_rate_idle_threshold", "40");
				k.setNode(policy + "/zzmoove/sampling_rate_sleep_multiplier", "4");
				k.setNode(policy + "/zzmoove/scaling_block_cycles", "0");
				k.setNode(policy + "/zzmoove/scaling_block_temp", "0");
				k.setNode(policy + "/zzmoove/scaling_trip_temp", "0");
				k.setNode(policy + "/zzmoove/scaling_block_freq", "0");
				k.setNode(policy + "/zzmoove/scaling_block_threshold", "0");
				k.setNode(policy + "/zzmoove/scaling_block_force_down", "2");
				k.setNode(policy + "/zzmoove/scaling_fastdown_freq", "0");
				k.setNode(policy + "/zzmoove/scaling_fastdown_up_threshold", "95");
				k.setNode(policy + "/zzmoove/scaling_fastdown_down_threshold", "90");
				k.setNode(policy + "/zzmoove/scaling_responsiveness_freq", "0");
				k.setNode(policy + "/zzmoove/scaling_responsiveness_up_threshold", "0");
				k.setNode(policy + "/zzmoove/scaling_proportional", "0");
				k.setNode(policy + "/zzmoove/inputboost_cycles", "0");
				k.setNode(policy + "/zzmoove/inputboost_up_threshold", "80");
				k.setNode(policy + "/zzmoove/inputboost_punch_cycles", "20");
				k.setNode(policy + "/zzmoove/inputboost_punch_freq", cpu.fitFrequency(1728000) + "");
				k.setNode(policy + "/zzmoove/inputboost_punch_on_fingerdown", "1");
				k.setNode(policy + "/zzmoove/inputboost_punch_on_fingermove", "0");
				k.setNode(policy + "/zzmoove/inputboost_punch_on_epenmove", "0");
				k.setNode(policy + "/zzmoove/inputboost_typingbooster_up_threshold", "40");
				k.setNode(policy + "/zzmoove/inputboost_typingbooster_cores", "3");
				k.setNode(policy + "/zzmoove/music_max_freq", cpu.fitFrequency(1497600) + "");
				k.setNode(policy + "/zzmoove/music_min_freq", cpu.fitFrequency(422400) + "");
				k.setNode(policy + "/zzmoove/music_min_cores", "2");
				k.setNode(policy + "/zzmoove/smooth_up", "70");
				k.setNode(policy + "/zzmoove/smooth_up_sleep", "100");
				k.setNode(policy + "/zzmoove/up_threshold", "60");
				k.setNode(policy + "/zzmoove/up_threshold_hotplug1", "65");
				k.setNode(policy + "/zzmoove/up_threshold_hotplug2", "75");
				k.setNode(policy + "/zzmoove/up_threshold_hotplug3", "85");
				k.setNode(policy + "/zzmoove/up_threshold_hotplug4", "68");
				k.setNode(policy + "/zzmoove/up_threshold_hotplug5", "68");
				k.setNode(policy + "/zzmoove/up_threshold_hotplug6", "68");
				k.setNode(policy + "/zzmoove/up_threshold_hotplug7", "68");
				k.setNode(policy + "/zzmoove/up_threshold_hotplug_freq1", cpu.fitFrequency(422400) + "");
				k.setNode(policy + "/zzmoove/up_threshold_hotplug_freq2", cpu.fitFrequency(1267200) + "");
				k.setNode(policy + "/zzmoove/up_threshold_hotplug_freq3", cpu.fitFrequency(1728000) + "");
				k.setNode(policy + "/zzmoove/up_threshold_hotplug_freq4", "0");
				k.setNode(policy + "/zzmoove/up_threshold_hotplug_freq5", "0");
				k.setNode(policy + "/zzmoove/up_threshold_hotplug_freq6", "0");
				k.setNode(policy + "/zzmoove/up_threshold_hotplug_freq7", "0");
				k.setNode(policy + "/zzmoove/up_threshold_sleep", "100");
				break;
			case "ondemand":
				k.setNode(policy + "/ondemand/down_differential", "2");
				k.setNode(policy + "/ondemand/ignore_nice_load", "0");
				k.setNode(policy + "/ondemand/input_boost", "1");
				k.setNode(policy + "/ondemand/io_is_busy", "0");
				k.setNode(policy + "/ondemand/optimal_freq", cpu.fitPercentage(0.6) + "");
				k.setNode(policy + "/ondemand/powersave_bias", "0");
				k.setNode(policy + "/ondemand/sampling_down_factor", "1");
				k.setNode(policy + "/ondemand/sampling_rate", "20000");
				k.setNode(policy + "/ondemand/sampling_rate_min", "10000");
				k.setNode(policy + "/ondemand/sync_freq", cpu.fitPercentage(0.5) + "");
				k.setNode(policy + "/ondemand/up_threshold", "90");
				k.setNode(policy + "/ondemand/up_threshold_any_cpu_load", "90");
				k.setNode(policy + "/ondemand/up_threshold_multi_core", "80");
				break;
			}
		case PROFILE_GAMING: //gaming
			switch (governor) {
			case "interactive":
				k.setNode(policy + "/interactive/hispeed_freq", cpu.fitPercentage(0.8) + "");
				k.setNode(policy + "/interactive/sync_freq", cpu.fitPercentage(0.5) + "");
				k.setNode(policy + "/interactive/up_threshold_any_cpu_freq", cpu.fitPercentage(0.7) + "");
				k.setNode(policy + "/blu_active/above_hispeed_delay",
						"20000 " + cpu.fitPercentage(0.5) + ":40000 " +
								cpu.fitPercentage(0.7) + ":20000 " +
								cpu.fitPercentage(0.85) + ":80000 " +
								cpu.fitPercentage(1) + ":100000");
				k.setNode(policy + "/interactive/go_hispeed_load", "80");
				k.setNode(policy + "/interactive/target_loads", "50");
				k.setNode(policy + "/interactive/up_threshold_any_cpu_load", "70");
				k.setNode(policy + "/interactive/io_is_busy", "1");
				k.setNode(policy + "/interactive/timer_rate", "20000");
				k.setNode(policy + "/interactive/timer_slack", "20000");
				k.setNode(policy + "/interactive/min_sample_time", "80000");
				k.setNode(policy + "/interactive/boostpulse_duration", "80000");
				break;
			case "blu_active":
				k.setNode(policy + "/blu_active/above_hispeed_delay",
						"20000 " + cpu.fitPercentage(0.5) + ":40000 " +
								cpu.fitPercentage(0.7) + ":20000 " +
								cpu.fitPercentage(0.85) + ":80000 " +
								cpu.fitPercentage(1) + ":100000");
				k.setNode(policy + "/blu_active/align_windows", "1");
				k.setNode(policy + "/blu_active/fastlane", "1");
				k.setNode(policy + "/blu_active/fastlane_threshold", "25");
				k.setNode(policy + "/blu_active/go_hispeed_load", "80");
				k.setNode(policy + "/blu_active/hispeed_freq", cpu.fitPercentage(0.8) + "");
				k.setNode(policy + "/blu_active/io_is_busy", "0");
				k.setNode(policy + "/blu_active/min_sample_time", "20000");
				k.setNode(policy + "/blu_active/target_loads", "50");
				k.setNode(policy + "/blu_active/timer_rate", "20000");
				k.setNode(policy + "/blu_active/timer_slack", "20000");
				break;
			case "zzmoove":
				k.setNode(policy + "/zzmoove/auto_adjust_freq_thresholds", "0");
				k.setNode(policy + "/zzmoove/disable_hotplug", "1");
				k.setNode(policy + "/zzmoove/disable_hotplug_sleep", "1");
				k.setNode(policy + "/zzmoove/down_threshold", "20");
				k.setNode(policy + "/zzmoove/down_threshold_hotplug1", "25");
				k.setNode(policy + "/zzmoove/down_threshold_hotplug2", "35");
				k.setNode(policy + "/zzmoove/down_threshold_hotplug3", "45");
				k.setNode(policy + "/zzmoove/down_threshold_hotplug4", "55");
				k.setNode(policy + "/zzmoove/down_threshold_hotplug5", "55");
				k.setNode(policy + "/zzmoove/down_threshold_hotplug6", "55");
				k.setNode(policy + "/zzmoove/down_threshold_hotplug7", "55");
				k.setNode(policy + "/zzmoove/down_threshold_hotplug_freq1", cpu.fitFrequency(422400) + "");
				k.setNode(policy + "/zzmoove/down_threshold_hotplug_freq2", cpu.fitFrequency(1190400) + "");
				k.setNode(policy + "/zzmoove/down_threshold_hotplug_freq3", cpu.fitFrequency(1574400) + "");
				k.setNode(policy + "/zzmoove/down_threshold_hotplug_freq4", "0");
				k.setNode(policy + "/zzmoove/down_threshold_hotplug_freq5", "0");
				k.setNode(policy + "/zzmoove/down_threshold_hotplug_freq6", "0");
				k.setNode(policy + "/zzmoove/down_threshold_hotplug_freq7", "0");
				k.setNode(policy + "/zzmoove/down_threshold_sleep", "60");
				k.setNode(policy + "/zzmoove/early_demand", "1");
				k.setNode(policy + "/zzmoove/early_demand_sleep", "1");
				k.setNode(policy + "/zzmoove/fast_scaling_up", "0");
				k.setNode(policy + "/zzmoove/fast_scaling_down", "0");
				k.setNode(policy + "/zzmoove/fast_scaling_sleep_up", "2");
				k.setNode(policy + "/zzmoove/fast_scaling_sleep_down", "0");
				k.setNode(policy + "/zzmoove/afs_threshold1", "30");
				k.setNode(policy + "/zzmoove/afs_threshold2", "50");
				k.setNode(policy + "/zzmoove/afs_threshold3", "70");
				k.setNode(policy + "/zzmoove/afs_threshold4", "90");
				k.setNode(policy + "/zzmoove/freq_limit", "0");
				k.setNode(policy + "/zzmoove/freq_limit_sleep", cpu.fitFrequency(729600) + "");
				k.setNode(policy + "/zzmoove/grad_up_threshold", "25");
				k.setNode(policy + "/zzmoove/grad_up_threshold_sleep", "28");
				k.setNode(policy + "/zzmoove/hotplug_block_up_cycles", "2");
				k.setNode(policy + "/zzmoove/block_up_multiplier_hotplug1", "1");
				k.setNode(policy + "/zzmoove/block_up_multiplier_hotplug2", "1");
				k.setNode(policy + "/zzmoove/block_up_multiplier_hotplug3", "1");
				k.setNode(policy + "/zzmoove/block_up_multiplier_hotplug4", "1");
				k.setNode(policy + "/zzmoove/block_up_multiplier_hotplug5", "1");
				k.setNode(policy + "/zzmoove/block_up_multiplier_hotplug6", "1");
				k.setNode(policy + "/zzmoove/block_up_multiplier_hotplug7", "1");
				k.setNode(policy + "/zzmoove/hotplug_block_down_cycles", "20");
				k.setNode(policy + "/zzmoove/block_down_multiplier_hotplug1", "1");
				k.setNode(policy + "/zzmoove/block_down_multiplier_hotplug2", "1");
				k.setNode(policy + "/zzmoove/block_down_multiplier_hotplug3", "1");
				k.setNode(policy + "/zzmoove/block_down_multiplier_hotplug4", "1");
				k.setNode(policy + "/zzmoove/block_down_multiplier_hotplug5", "1");
				k.setNode(policy + "/zzmoove/block_down_multiplier_hotplug6", "1");
				k.setNode(policy + "/zzmoove/block_down_multiplier_hotplug7", "1");
				k.setNode(policy + "/zzmoove/hotplug_stagger_up", "0");
				k.setNode(policy + "/zzmoove/hotplug_stagger_down", "0");
				k.setNode(policy + "/zzmoove/hotplug_idle_threshold", "0");
				k.setNode(policy + "/zzmoove/hotplug_idle_freq", "0");
				k.setNode(policy + "/zzmoove/hotplug_sleep", "1");
				k.setNode(policy + "/zzmoove/hotplug_engage_freq", "0");
				k.setNode(policy + "/zzmoove/hotplug_max_limit", "0");
				k.setNode(policy + "/zzmoove/hotplug_min_limit", "0");
				k.setNode(policy + "/zzmoove/hotplug_lock", "0");
				k.setNode(policy + "/zzmoove/ignore_nice_load", "0");
				k.setNode(policy + "/zzmoove/sampling_down_factor", "4");
				k.setNode(policy + "/zzmoove/sampling_down_max_momentum", "60");
				k.setNode(policy + "/zzmoove/sampling_down_momentum_sensitivity", "20");
				k.setNode(policy + "/zzmoove/sampling_rate", "60000");
				k.setNode(policy + "/zzmoove/sampling_rate_idle", "100000");
				k.setNode(policy + "/zzmoove/sampling_rate_idle_delay", "0");
				k.setNode(policy + "/zzmoove/sampling_rate_idle_threshold", "40");
				k.setNode(policy + "/zzmoove/sampling_rate_sleep_multiplier", "4");
				k.setNode(policy + "/zzmoove/scaling_block_cycles", "0");
				k.setNode(policy + "/zzmoove/scaling_block_temp", "65");
				k.setNode(policy + "/zzmoove/scaling_block_cycles", "15");
				k.setNode(policy + "/zzmoove/scaling_trip_temp", "0");
				k.setNode(policy + "/zzmoove/scaling_block_freq", cpu.fitFrequency(1574400) + "");
				k.setNode(policy + "/zzmoove/scaling_block_threshold", "5");
				k.setNode(policy + "/zzmoove/scaling_block_force_down", "3");
				k.setNode(policy + "/zzmoove/scaling_fastdown_freq", "0");
				k.setNode(policy + "/zzmoove/scaling_fastdown_up_threshold", "95");
				k.setNode(policy + "/zzmoove/scaling_fastdown_down_threshold", "90");
				k.setNode(policy + "/zzmoove/scaling_responsiveness_freq", "0");
				k.setNode(policy + "/zzmoove/scaling_responsiveness_up_threshold", "0");
				k.setNode(policy + "/zzmoove/scaling_proportional", "1");
				k.setNode(policy + "/zzmoove/inputboost_cycles", "0");
				k.setNode(policy + "/zzmoove/inputboost_up_threshold", "80");
				k.setNode(policy + "/zzmoove/inputboost_punch_cycles", "20");
				k.setNode(policy + "/zzmoove/inputboost_punch_freq", cpu.fitFrequency(1728000) + "");
				k.setNode(policy + "/zzmoove/inputboost_punch_on_fingerdown", "1");
				k.setNode(policy + "/zzmoove/inputboost_punch_on_fingermove", "0");
				k.setNode(policy + "/zzmoove/inputboost_punch_on_epenmove", "0");
				k.setNode(policy + "/zzmoove/inputboost_typingbooster_up_threshold", "40");
				k.setNode(policy + "/zzmoove/inputboost_typingbooster_cores", "3");
				k.setNode(policy + "/zzmoove/music_max_freq", cpu.fitFrequency(1497600) + "");
				k.setNode(policy + "/zzmoove/music_min_freq", cpu.fitFrequency(422400) + "");
				k.setNode(policy + "/zzmoove/music_min_cores", "2");
				k.setNode(policy + "/zzmoove/smooth_up", "70");
				k.setNode(policy + "/zzmoove/smooth_up_sleep", "100");
				k.setNode(policy + "/zzmoove/up_threshold", "60");
				k.setNode(policy + "/zzmoove/up_threshold_hotplug1", "65");
				k.setNode(policy + "/zzmoove/up_threshold_hotplug2", "75");
				k.setNode(policy + "/zzmoove/up_threshold_hotplug3", "85");
				k.setNode(policy + "/zzmoove/up_threshold_hotplug4", "68");
				k.setNode(policy + "/zzmoove/up_threshold_hotplug5", "68");
				k.setNode(policy + "/zzmoove/up_threshold_hotplug6", "68");
				k.setNode(policy + "/zzmoove/up_threshold_hotplug7", "68");
				k.setNode(policy + "/zzmoove/up_threshold_hotplug_freq1", cpu.fitFrequency(652800) + "");
				k.setNode(policy + "/zzmoove/up_threshold_hotplug_freq2", cpu.fitFrequency(1267200) + "");
				k.setNode(policy + "/zzmoove/up_threshold_hotplug_freq3", cpu.fitFrequency(1958400) + "");
				k.setNode(policy + "/zzmoove/up_threshold_hotplug_freq4", "0");
				k.setNode(policy + "/zzmoove/up_threshold_hotplug_freq5", "0");
				k.setNode(policy + "/zzmoove/up_threshold_hotplug_freq6", "0");
				k.setNode(policy + "/zzmoove/up_threshold_hotplug_freq7", "0");
				k.setNode(policy + "/zzmoove/up_threshold_sleep", "100");
				break;
			case "ondemand":
				k.setNode(policy + "/ondemand/down_differential", "2");
				k.setNode(policy + "/ondemand/ignore_nice_load", "0");
				k.setNode(policy + "/ondemand/input_boost", "1");
				k.setNode(policy + "/ondemand/io_is_busy", "0");
				k.setNode(policy + "/ondemand/optimal_freq", cpu.fitPercentage(0.6) + "");
				k.setNode(policy + "/ondemand/powersave_bias", "0");
				k.setNode(policy + "/ondemand/sampling_down_factor", "1");
				k.setNode(policy + "/ondemand/sampling_rate", "20000");
				k.setNode(policy + "/ondemand/sampling_rate_min", "10000");
				k.setNode(policy + "/ondemand/sync_freq", cpu.fitPercentage(0.5) + "");
				k.setNode(policy + "/ondemand/up_threshold", "90");
				k.setNode(policy + "/ondemand/up_threshold_any_cpu_load", "90");
				k.setNode(policy + "/ondemand/up_threshold_multi_core", "80");
				break;
			}
		}
	}

	@Override
	public void onReceive(final Context context, Intent intent) {
		final String action = intent.getAction();
		if (!"android.intent.action.BOOT_COMPLETED".equals(action)) {
			Log.w(LOG_TAG, "Wrong intent action - \"" + action + "\"");
			return;
		}
		final Context appContext = context.getApplicationContext();
		if (!(appContext instanceof DynatweakApp)) {
			Log.w(LOG_TAG, "ApplicationContext is not DynatweakApp");
			return;
		}
		final Properties config = ((DynatweakApp) appContext).getConfiguration();
		try {
			if (config.getProperty("smooth_interactive", "disabled").equals("enabled")) {
				final int profile = Integer.parseInt(config.getProperty("hotplug_profile", "0"));
				final int hotplug = Integer.parseInt(config.getProperty("interactive_profile", "1"));

				Intent intent1 = new Intent(context, TweakService.class);
				intent1.putExtra("profile", profile);
				intent1.putExtra("hotplug", hotplug);
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
					context.startForegroundService(intent1);
				} else {
					context.startService(intent1);
				}
			}
			final boolean dynatweak_service = config.getProperty("dynatweak_service", "disabled").equals("enabled");
			if (dynatweak_service) {
				Intent intent2 = new Intent(context, MonitorService.class);
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
					context.startForegroundService(intent2);
				} else {
					context.startService(intent2);
				}
			}
		} catch (Throwable ex) {
			Log.e(LOG_TAG, "Boot receiver failed", ex);
			Toast.makeText(context, R.string.boot_exception, Toast.LENGTH_SHORT).show();
		}
	}
}
