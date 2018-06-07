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
		Log.i(LOG_TAG, "Start tweaking...");
		Kernel k = Kernel.getInstance();
		Kernel.CpuCore cpu0 = k.cpuCores.get(0);

		// CPU Hotplug
		if (k.hasNode("/system/bin/mpdecision")) {
			k.runAsRoot("stop mpdecision");
		}
		k.setNode("/proc/hps/enabled", "0");

		// Thermal
		if (k.hasCoreControl()) {
			k.setCoreControlMask(0);
		}

		k.commit(); // Hotplug & thermal tweaks should be committed before CPU tweaking

		// Entropy
		k.setSysctl("kernel.random.read_wakeup_threshold", "128");
		k.setSysctl("kernel.random.write_wakeup_threshold", "512");

		// VM
		k.setSysctl("vm.dirty_expire_centisecs", "3000");
		k.setSysctl("vm.dirty_writeback_centisecs", "1000");

		// Misc
		k.setNode("/sys/kernel/fast_charge/force_fast_charge", "1");
		k.setNode("/sys/kernel/sched/arch_power", "1");
		k.setNode("/sys/module/workqueue/parameters/power_efficent", "Y");

		// IO
		k.setNode("/sys/module/sync/parameters/fsync_enabled", "N");
		List<String> block = k.listBlockDevices();
		for (String i : block) {
			Log.i(LOG_TAG, "block device detected: " + i);
			if (k.hasNode(i + "/queue/scheduler")) {
				k.setNode(i + "/queue/iostats", "0");
				k.setNode(i + "/queue/add_random", "0");
				k.setNode(i + "/queue/read_ahead_kb", "1024");
				k.setNode(i + "/queue/rq_affinity", "1");
				k.setNode(i + "/queue/rotational", "0");
				k.setNode(i + "/queue/nr_requests", "512");
				List<String> schedulers = k.listBlockAvailableScheduler(i + "/queue/scheduler");
				if (schedulers.contains("maple")) {
					k.setNode(i + "/queue/scheduler", "maple");
				} else if (schedulers.contains("fiops")) {
					k.setNode(i + "/queue/scheduler", "fiops");
				} else if (schedulers.contains("bfq")) {
					k.setNode(i + "/queue/scheduler", "bfq");
					k.setNode(i + "/queue/iosched/low_latency", "1");
					k.setNode(i + "/queue/iosched/slice_idle", "0");
				} else if (schedulers.contains("cfq")) {
					k.setNode(i + "/queue/scheduler", "cfq");
					k.setNode(i + "/queue/iosched/low_latency", "1");
					k.setNode(i + "/queue/iosched/slice_idle", "0");
				}
			}
		}

		List<Kernel.ClusterPolicy> allPolicy = k.getAllPolicies();
		List<String> allGovernors = cpu0.getScalingAvailableGovernors();
		List<String> governor = new ArrayList<>();
		List<Integer> profiles = new ArrayList<>();
		final boolean multiPolicy = allPolicy.size() > 1;

		if (!multiPolicy) {
			Log.d(LOG_TAG, "single-policy profile=" + profile);
			switch (profile) {
			case PROFILE_DISABLED:
				break;
			case PROFILE_POWERSAVE: {
				final String[] preferList = {};
				governor.add(preferGovernor(allGovernors, preferList));
				break;
			}
			case PROFILE_BALANCED: {
				final String[] preferList = {};
				governor.add(preferGovernor(allGovernors, preferList));
				break;
			}
			case PROFILE_PERFORMANCE: {
				final String[] preferList = {"ondemand"};
				governor.add(preferGovernor(allGovernors, preferList));
				break;
			}
			case PROFILE_GAMING: {
				final String[] preferList = {"performance", "ondemand"};
				governor.add(preferGovernor(allGovernors, preferList));
				break;
			}
			}
			profiles.add(profile);
		} else {
			Log.d(LOG_TAG, "multi-policy profile=" + profile);
			for (int i = 0; i < allPolicy.size(); i++) {
				switch (profile) {
				case PROFILE_DISABLED:
					break;
				case PROFILE_POWERSAVE: {
					if (i == 0) {
						final String[] preferList = {};
						governor.add(preferGovernor(allGovernors, preferList));
					} else {
						final String[] preferList = {"powersave"};
						governor.add(preferGovernor(allGovernors, preferList));
					}
					profiles.add(PROFILE_POWERSAVE);
					break;
				}
				case PROFILE_BALANCED: {
					final String[] preferList = {};
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
						final String[] preferList = {"ondemand"};
						governor.add(preferGovernor(allGovernors, preferList));
						profiles.add(PROFILE_PERFORMANCE);
					} else {
						final String[] preferList = {};
						governor.add(preferGovernor(allGovernors, preferList));
						profiles.add(PROFILE_BALANCED);
					}
					break;
				}
				case PROFILE_GAMING: {
					final String[] preferList = {"performance", "ondemand"};
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
			if (multiPolicy) {
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
			if (multiPolicy) {
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
			final String[][] driverNodes = {
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

		Log.i(LOG_TAG, "Finished tweaking...");
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
				k.setNode(policy + "/interactive/max_freq_hysteresis", "80000");

				// use scheduler tricks
				k.setNode(policy + "/interactive/use_sched_load", "1");
				k.setNode(policy + "/interactive/use_migration_notif", "1");
				k.setNode(policy + "/interactive/fast_ramp_down", "1");
				k.setNode(policy + "/interactive/enable_prediction", "1");
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
				k.setNode(policy + "/interactive/max_freq_hysteresis", "80000");

				// use scheduler tricks
				k.setNode(policy + "/interactive/use_sched_load", "1");
				k.setNode(policy + "/interactive/use_migration_notif", "1");
				k.setNode(policy + "/interactive/fast_ramp_down", "1");
				k.setNode(policy + "/interactive/enable_prediction", "1");
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
				k.setNode(policy + "/interactive/max_freq_hysteresis", "40000");

				// use scheduler tricks
				k.setNode(policy + "/interactive/use_sched_load", "1");
				k.setNode(policy + "/interactive/use_migration_notif", "1");
				k.setNode(policy + "/interactive/fast_ramp_down", "0");
				k.setNode(policy + "/interactive/enable_prediction", "1");
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
				k.setNode(policy + "/interactive/above_hispeed_delay",
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

				// use scheduler tricks
				k.setNode(policy + "/interactive/use_sched_load", "1");
				k.setNode(policy + "/interactive/use_migration_notif", "1");
				k.setNode(policy + "/interactive/fast_ramp_down", "0");
				k.setNode(policy + "/interactive/enable_prediction", "1");
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
