package org.hexian000.dynatweak;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import android.util.SparseArray;
import android.widget.Toast;
import org.hexian000.dynatweak.api.Kernel;

import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

import static org.hexian000.dynatweak.Dynatweak.LOG_TAG;

/**
 * Created by hexian on 2017/6/18.
 * Boot time tweaks
 */
public class BootReceiver extends BroadcastReceiver {

	static synchronized void tweak(int hotplug, int profile) throws IOException {
		Log.i(LOG_TAG, "Start tweaking...");
		Kernel k = Kernel.getInstance();
		Kernel.CpuCore cpu0 = k.getCpuCore(0);

		// CPU Hotplug
		k.runAsRoot(
				"mpdecision_pid=`pgrep mpdecision` && ( stop mpdecision ; killall mpdecision ; wait $mpdecision_pid )");
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
		k.setSysctl("vm.dirty_ratio", "25");
		k.setSysctl("vm.dirty_background_ratio", "20");
		k.setSysctl("vm.dirty_expire_centisecs", "3000");
		k.setSysctl("vm.dirty_writeback_centisecs", "2000");
		k.setSysctl("vm.swappiness", "0");
		k.setSysctl("vm.vfs_cache_pressure", "50");

		// Misc
		k.setNode("/sys/kernel/fast_charge/force_fast_charge", "1");
		k.setNode("/sys/kernel/sched/arch_power", "1");
		k.setNode("/sys/module/workqueue/parameters/power_efficent", "Y");

		// IO
		tweakBlockDevices(k, new String[]{"/cache", "/data"});

		List<Kernel.FrequencyPolicy> allPolicy = k.getAllPolicies();
		final String defaultGovernor = "interactive";
		Set<String> allGovernors = new HashSet<>(cpu0.getScalingAvailableGovernors());
		List<String> governor = new ArrayList<>();
		List<Integer> profiles = new ArrayList<>();
		final boolean multiPolicy = allPolicy.size() > 1;

		if (!multiPolicy) {
			Log.d(LOG_TAG, "single-policy profile=" + profile);
			switch (profile) {
			case Dynatweak.Profiles.DISABLED:
				break;
			case Dynatweak.Profiles.POWERSAVE: {
				final String[] preferList = {defaultGovernor};
				governor.add(preferGovernor(allGovernors, preferList));
				break;
			}
			case Dynatweak.Profiles.BALANCED: {
				final String[] preferList = {defaultGovernor};
				governor.add(preferGovernor(allGovernors, preferList));
				break;
			}
			case Dynatweak.Profiles.PERFORMANCE: {
				final String[] preferList = {"ondemand", defaultGovernor};
				governor.add(preferGovernor(allGovernors, preferList));
				break;
			}
			case Dynatweak.Profiles.GAMING: {
				final String[] preferList = {"performance", "ondemand", defaultGovernor};
				governor.add(preferGovernor(allGovernors, preferList));
				break;
			}
			}
			profiles.add(profile);
		} else {
			Log.d(LOG_TAG, "multi-policy profile=" + profile);
			for (int i = 0; i < allPolicy.size(); i++) {
				switch (profile) {
				case Dynatweak.Profiles.DISABLED:
					break;
				case Dynatweak.Profiles.POWERSAVE: {
					if (i == 0) {
						final String[] preferList = {defaultGovernor};
						governor.add(preferGovernor(allGovernors, preferList));
					} else {
						final String[] preferList = {defaultGovernor};
						governor.add(preferGovernor(allGovernors, preferList));
					}
					profiles.add(Dynatweak.Profiles.POWERSAVE);
					break;
				}
				case Dynatweak.Profiles.BALANCED: {
					final String[] preferList = {defaultGovernor};
					governor.add(preferGovernor(allGovernors, preferList));
					if (i == 0) {
						profiles.add(Dynatweak.Profiles.BALANCED);
					} else {
						profiles.add(Dynatweak.Profiles.POWERSAVE);
					}
					break;
				}
				case Dynatweak.Profiles.PERFORMANCE: {
					if (i == 0) {
						final String[] preferList = {defaultGovernor};
						governor.add(preferGovernor(allGovernors, preferList));
						profiles.add(Dynatweak.Profiles.PERFORMANCE);
					} else {
						final String[] preferList = {defaultGovernor};
						governor.add(preferGovernor(allGovernors, preferList));
						profiles.add(Dynatweak.Profiles.BALANCED);
					}
					break;
				}
				case Dynatweak.Profiles.GAMING: {
					final String[] preferList = {"performance", defaultGovernor};
					governor.add(preferGovernor(allGovernors, preferList));
					profiles.add(Dynatweak.Profiles.GAMING);
					break;
				}
				}
			}
		}

		// CPU Frequency
		for (int i = 0; i < k.getCpuCoreCount(); i++) {
			Kernel.CpuCore cpu = k.getCpuCore(i);
			for (int trial = 0; trial < 3; trial++) {
				try {
					cpu.trySetOnline(true);
					if (profile == Dynatweak.Profiles.GAMING) {
						cpu.setScalingMaxFrequency(cpu.getMaxFrequency());
					}
					// Per cpu governor tweak
					if (profile != Dynatweak.Profiles.DISABLED) {
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
			Kernel.FrequencyPolicy frequencyPolicy = allPolicy.get(i);
			if (profile != Dynatweak.Profiles.DISABLED) {
				Log.d(LOG_TAG, "tweaking policy " + i +
						": governor=" + governor.get(i) + ", profile=" + profiles.get(i));
			}
			Kernel.CpuCore cpu = k.getCpuCore(frequencyPolicy.getStartCpu());
			// Qualcomm core control
			if (k.hasNode(cpu.getPath() + "/core_ctl")) {
				Log.i(LOG_TAG, "policy" + i + ": core_ctl detected");
				k.setNode(cpu.getPath() + "/core_ctl/max_cpus", frequencyPolicy.getCpuCount() + "");
				k.setNode(cpu.getPath() + "/core_ctl/offline_delay_ms", "100");
				if (!k.readNode(cpu.getPath() + "/core_ctl/is_big_cluster").equals("0")) {
					switch (profile) { // big cluster
					case Dynatweak.Profiles.POWERSAVE:
						k.setNode(cpu.getPath() + "/core_ctl/busy_down_thres",
								setAllCoresTheSame("40", frequencyPolicy.getCpuCount()));
						k.setNode(cpu.getPath() + "/core_ctl/busy_up_thres",
								setAllCoresTheSame("90", frequencyPolicy.getCpuCount()));
						k.setNode(cpu.getPath() + "/core_ctl/min_cpus", "0");
						break;
					case Dynatweak.Profiles.BALANCED:
						k.setNode(cpu.getPath() + "/core_ctl/busy_down_thres",
								setAllCoresTheSame("40", frequencyPolicy.getCpuCount()));
						k.setNode(cpu.getPath() + "/core_ctl/busy_up_thres",
								setAllCoresTheSame("60", frequencyPolicy.getCpuCount()));
						k.setNode(cpu.getPath() + "/core_ctl/min_cpus", "0");
						break;
					case Dynatweak.Profiles.PERFORMANCE:
						k.setNode(cpu.getPath() + "/core_ctl/busy_down_thres",
								setAllCoresTheSame("30", frequencyPolicy.getCpuCount()));
						k.setNode(cpu.getPath() + "/core_ctl/busy_up_thres",
								setAllCoresTheSame("60", frequencyPolicy.getCpuCount()));
						k.setNode(cpu.getPath() + "/core_ctl/min_cpus",
								Math.min(frequencyPolicy.getCpuCount(), 2) + "");
						break;
					case Dynatweak.Profiles.GAMING:
						k.setNode(cpu.getPath() + "/core_ctl/busy_down_thres",
								setAllCoresTheSame("0", frequencyPolicy.getCpuCount()));
						k.setNode(cpu.getPath() + "/core_ctl/busy_up_thres",
								setAllCoresTheSame("0", frequencyPolicy.getCpuCount()));
						k.setNode(cpu.getPath() + "/core_ctl/min_cpus", frequencyPolicy.getCpuCount() + "");
						break;
					}
				} else { // little cluster
					switch (profile) {
					case Dynatweak.Profiles.DISABLED:
						break;
					case Dynatweak.Profiles.POWERSAVE:
						k.setNode(cpu.getPath() + "/core_ctl/busy_down_thres",
								setAllCoresTheSame("10", frequencyPolicy.getCpuCount()));
						k.setNode(cpu.getPath() + "/core_ctl/busy_up_thres",
								setAllCoresTheSame("30", frequencyPolicy.getCpuCount()));
						k.setNode(cpu.getPath() + "/core_ctl/min_cpus",
								Math.min(frequencyPolicy.getCpuCount(), 2) + "");
						break;
					case Dynatweak.Profiles.BALANCED:
					case Dynatweak.Profiles.PERFORMANCE:
					case Dynatweak.Profiles.GAMING:
						k.setNode(cpu.getPath() + "/core_ctl/busy_down_thres",
								setAllCoresTheSame("0", frequencyPolicy.getCpuCount()));
						k.setNode(cpu.getPath() + "/core_ctl/busy_up_thres",
								setAllCoresTheSame("0", frequencyPolicy.getCpuCount()));
						k.setNode(cpu.getPath() + "/core_ctl/min_cpus", frequencyPolicy.getCpuCount() + "");
						break;
					}
				}
			}
			// Per policy
			if (profile != Dynatweak.Profiles.DISABLED) {
				String policy = frequencyPolicy.getPolicyPath();
				if (policy != null) {
					tweakGovernor(k, cpu, policy, governor.get(i), profiles.get(i));
				}
			}
		}

		// CPU big.LITTLE
		switch (profile) {
		case Dynatweak.Profiles.DISABLED:
			break;
		case Dynatweak.Profiles.POWERSAVE:
			k.setSysctl("kernel.sched_downmigrate", "80");
			k.setSysctl("kernel.sched_upmigrate", "95");
			k.setSysctl("kernel.sched_spill_nr_run", "2");
			k.setSysctl("kernel.sched_spill_load", "90");
			break;
		case Dynatweak.Profiles.BALANCED:
			k.setSysctl("kernel.sched_downmigrate", "70");
			k.setSysctl("kernel.sched_upmigrate", "90");
			k.setSysctl("kernel.sched_spill_nr_run", "4");
			k.setSysctl("kernel.sched_spill_load", "90");
			break;
		case Dynatweak.Profiles.PERFORMANCE:
			k.setSysctl("kernel.sched_downmigrate", "20");
			k.setSysctl("kernel.sched_upmigrate", "80");
			k.setSysctl("kernel.sched_spill_nr_run", "8");
			k.setSysctl("kernel.sched_spill_load", "95");
			break;
		case Dynatweak.Profiles.GAMING:
			k.setSysctl("kernel.sched_downmigrate", "0");
			k.setSysctl("kernel.sched_upmigrate", "60");
			k.setSysctl("kernel.sched_spill_nr_run", "8");
			k.setSysctl("kernel.sched_spill_load", "95");
			break;
		}

		// MSM Performance
		if (k.hasNodeByRoot("/sys/module/msm_performance/parameters")) {
			Log.i(LOG_TAG, "msm_performance detected");
			final String msm_performance = "/sys/module/msm_performance/parameters/";
			StringBuilder cpu_max_freq = new StringBuilder();
			StringBuilder cpu_min_freq = new StringBuilder();
			for (int i = 0; i < k.getCpuCoreCount(); i++) {
				Kernel.CpuCore cpu = k.getCpuCore(i);
				cpu_max_freq.append(cpu.getId()).append(':').append(cpu.getMaxFrequency()).append(' ');
				cpu_min_freq.append(cpu.getId()).append(':').append(cpu.getMinFrequency()).append(' ');
			}
			k.setNode(msm_performance + "cpu_max_freq", cpu_max_freq.toString());
			k.setNode(msm_performance + "cpu_min_freq", cpu_min_freq.toString());
			k.setNode(msm_performance + "workload_detect", "1");
			switch (profile) {
			case Dynatweak.Profiles.PERFORMANCE:
			case Dynatweak.Profiles.GAMING:
				k.setNode(msm_performance + "touchboost", "1");
				k.setNode(msm_performance + "workload_modes/aggr_mode", "1");
			default:
				k.setNode(msm_performance + "touchboost", "0");
				k.setNode(msm_performance + "workload_modes/aggr_mode", "0");
			}
			k.setNode(msm_performance + "workload_modes/aggr_iobusy", "0");
		}

		// CPU Boost
		k.setNode("/sys/module/cpu_boost/parameters/boost_ms", "40");
		k.setNode("/sys/module/cpu_boost/parameters/sync_threshold", cpu0.fitPercentage(0.3) + "");
		StringBuilder boostFreq = new StringBuilder(), boostFreq_s2 = new StringBuilder();
		for (int i = 0; i < k.getCpuCoreCount(); i++) {
			Kernel.CpuCore cpu = k.getCpuCore(i);
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
		switch (profile) {
		case Dynatweak.Profiles.PERFORMANCE:
		case Dynatweak.Profiles.GAMING:
			k.setNode("/sys/module/cpu_boost/input_boost_enabled", "1");
		default:
			k.setNode("/sys/module/cpu_boost/input_boost_enabled", "0");
		}

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
		Set<String> gpu_available_governors = new HashSet<>();
		if (k.hasNode(gpuNodeRoot + "/devfreq/governor")) {
			String[] list = k.readNode(gpuNodeRoot + "/devfreq/available_governors").split(Pattern.quote(" "));
			Collections.addAll(gpu_available_governors, list);
		}

		switch (profile) {
		case Dynatweak.Profiles.DISABLED:
			break;
		case Dynatweak.Profiles.POWERSAVE: // maximize battery life
		{
			final String g = preferGovernor(gpu_available_governors,
					new String[]{"powersave", "msm-adreno-tz"});
			if (g != null) {
				k.setNode(gpuNodeRoot + "/devfreq/governor", g);
			} else if (num_pwrlevels != null) {
				k.setNode(gpuNodeRoot + "/max_pwrlevel", (num_pwrlevels - 1) + "");
				k.setNode(gpuNodeRoot + "/min_pwrlevel", (num_pwrlevels - 1) + "");
			}
		}
		break;
		case Dynatweak.Profiles.BALANCED: // governor controlled with idler
		{
			// Adreno Idler
			k.setNode("/sys/module/adreno_idler/parameters/adreno_idler_active", "Y");
			k.setNode("/sys/module/adreno_idler/parameters/adreno_idler_downdifferential", "40");
			k.setNode("/sys/module/adreno_idler/parameters/adreno_idler_idleworkload", "8000");
			k.setNode("/sys/module/adreno_idler/parameters/adreno_idler_idlewait", "50");
			final String g = preferGovernor(gpu_available_governors,
					new String[]{"msm-adreno-tz", "simple_ondemand", "simple", "ondemand"});
			if (g != null) {
				k.setNode(gpuNodeRoot + "/devfreq/governor", g);
			} else if (num_pwrlevels != null) {
				try {
					k.setNode(gpuNodeRoot + "/max_pwrlevel", "0");
					k.setNode(gpuNodeRoot + "/min_pwrlevel", (num_pwrlevels - 1) + "");
				} catch (Throwable ignore) {
				}
			}
		}
		break;
		case Dynatweak.Profiles.PERFORMANCE: // governor controlled without idler
		{
			k.setNode("/sys/module/adreno_idler/parameters/adreno_idler_active", "N");
			final String g = preferGovernor(gpu_available_governors,
					new String[]{"simple_ondemand", "ondemand", "simple", "msm-adreno-tz"});
			if (g != null) {
				k.setNode(gpuNodeRoot + "/devfreq/governor", g);
			} else if (num_pwrlevels != null) {
				try {
					k.setNode(gpuNodeRoot + "/max_pwrlevel", "0");
					k.setNode(gpuNodeRoot + "/min_pwrlevel", (num_pwrlevels - 1) + "");
				} catch (Throwable ignore) {
				}
			}
		}
		break;
		case Dynatweak.Profiles.GAMING: // maximize performance
		{
			final String g = preferGovernor(gpu_available_governors,
					new String[]{"performance", "simple_ondemand", "ondemand", "simple", "msm-adreno-tz"});
			if (g != null) {
				k.setNode(gpuNodeRoot + "/devfreq/governor", g);
			} else if (num_pwrlevels != null) {
				try {
					k.setNode(gpuNodeRoot + "/max_pwrlevel", "0");
					k.setNode(gpuNodeRoot + "/min_pwrlevel", "0");
				} catch (Throwable ignore) {
				}
			}
		}
		break;
		}

		// hotplug
		switch (hotplug) {
		case Dynatweak.Hotplugs.ALLCORES: // all cores
			k.setNode("/sys/devices/system/cpu/sched_mc_power_savings", "0");
			break;
		case Dynatweak.Hotplugs.HALFCORES: // half core
			final int cluster = k.getClusterCount();
			Log.d(LOG_TAG, "cluster count: " + cluster);
			boolean hasCoreControl = k.hasCoreControl();
			SparseArray<List<Kernel.CpuCore>> cpuMap = new SparseArray<>();
			for (int i = 0; i < k.getCpuCoreCount(); i++) {
				Kernel.CpuCore cpu = k.getCpuCore(i);
				List<Kernel.CpuCore> cpuList = cpuMap.get(cpu.getCluster());
				if (cpuList == null) {
					cpuList = new ArrayList<>();
					cpuMap.put(cpu.getCluster(), cpuList);
				}
				cpuList.add(cpu);
			}

			int mask = 0;
			for (int i = 0; i < cpuMap.size(); i++) {
				List<Kernel.CpuCore> cpuList = cpuMap.valueAt(i);
				for (int j = (cpuList.size() + 1) / 2; j < cpuList.size(); j++) {
					if (hasCoreControl) {
						mask |= 1 << cpuList.get(j).getId();
					} else {
						cpuList.get(j).setOnline(false);
					}
				}
			}
			if (hasCoreControl) {
				Log.d(LOG_TAG, "setCoreControlMask: " + mask);
				k.setCoreControlMask(mask);
			}
			k.setNode("/sys/devices/system/cpu/sched_mc_power_savings", "1");
			break;
		case Dynatweak.Hotplugs.DRIVER: // use hotplug driver
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

	private static String preferGovernor(Set<String> allGovernors, String[] names) {
		for (String name : names) {
			if (allGovernors.contains(name)) {
				return name;
			}
		}
		return null;
	}

	private static String setAllCoresTheSame(String value, int core) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < core; i++) {
			sb.append(value).append(' ');
		}
		return sb.toString();
	}

	private static void tweakGovernor(Kernel k, Kernel.CpuCore cpu, String policy, String governor, int profile)
			throws IOException {
		switch (profile) {
		case Dynatweak.Profiles.POWERSAVE: // powersave
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
		case Dynatweak.Profiles.BALANCED: // balanced
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
		case Dynatweak.Profiles.PERFORMANCE: // performance
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
		case Dynatweak.Profiles.GAMING: //gaming
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

	private static void tweakBlockDevices(final Kernel k, final String[] mountPoint) {
		Set<String> devices = new HashSet<>();
		for (String mount : mountPoint) {
			String path = k.getBlockDevice(mount);
			if (path != null) {
				devices.add("/sys" + path.substring(4));
			} else {
				Log.w(LOG_TAG, "mount point not found: " + mount);
			}
		}
		for (String path : devices) {
			if (k.hasNode(path + "/queue/scheduler")) {
				Log.i(LOG_TAG, "block device detected: " + path);
				k.setNode(path + "/queue/read_ahead_kb", "1024");
				List<String> schedulers = k.listBlockAvailableScheduler(path + "/queue/scheduler");
				if (schedulers.contains("bfq")) {
					k.setNode(path + "/queue/scheduler", "bfq");
					k.setNode(path + "/queue/iosched/low_latency", "1");
					k.setNode(path + "/queue/iosched/slice_idle", "0");
				} else if (schedulers.contains("cfq")) {
					k.setNode(path + "/queue/scheduler", "cfq");
					k.setNode(path + "/queue/iosched/low_latency", "1");
					k.setNode(path + "/queue/iosched/slice_idle", "0");
				}
			} else {
				Log.w(LOG_TAG, "block node not found: " + path + "/queue/scheduler");
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
		if (!(appContext instanceof Dynatweak)) {
			Log.w(LOG_TAG, "ApplicationContext is not Dynatweak");
			return;
		}
		final Properties config = ((Dynatweak) appContext).getConfiguration();
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
