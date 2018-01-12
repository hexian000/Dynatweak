package org.hexian000.dynatweak;

import android.os.Build;
import android.util.Log;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Created by hexian on 2017/6/18.
 * HTML device info generator
 */
class DeviceInfo {

	final CpuStat stat;
	private final boolean tzBuild = false;
	private final List<DeviceNode> nodes;

	DeviceInfo(Kernel k) {
		nodes = new ArrayList<>();
		DeviceNode soc = new SoC();
		if (soc.hasAny())
			nodes.add(soc);
		CPU cpu;
		stat = new CpuStat();
		for (Kernel.CpuCore cpuCore : k.cpuCores) {
			cpu = new CPU(cpuCore.getId(), stat);
			nodes.add(cpu);
			Log.d(Kernel.LOG_TAG, "cpu" + cpuCore.getId() + " in cluster " + cpuCore.getCluster() + " detected");
		}
		stat.initialize(k.cpuCores.size());
		DeviceNode gpu = new GPU();
		if (gpu.hasAny())
			nodes.add(gpu);
		nodes.add(stat);
		if (tzBuild) {
			nodes.add(new Sensors());
		}
		k.releaseRoot();
	}

	String getHtml() {
		StringBuilder sb = new StringBuilder();
		for (DeviceNode dev : nodes) {
			try {
				dev.generateHtml(sb);
			} catch (IOException ex) {
				Log.e(Kernel.LOG_TAG, "Device info error", ex);
			}
			sb.append("<br/>");
		}
		return sb.toString();
	}

	private interface DeviceNode {
		void generateHtml(StringBuilder out) throws IOException;

		boolean hasAny();
	}

	class CpuStat implements DeviceNode {

		long cpu_iowait[], cpu_idle[], cpu_total[];
		private int count;
		private double freq_all;
		private long last_iowait[], last_idle[], last_total[];
		private long cpu_all_idle, cpu_all_iowait, cpu_all_total;
		private long last_cpu_all_idle, last_cpu_all_iowait, last_cpu_all_total;
		private RandomAccessFile stat;

		void initialize(int count) {
			last_iowait = new long[count];
			last_idle = new long[count];
			last_total = new long[count];
			cpu_iowait = new long[count];
			cpu_idle = new long[count];
			cpu_total = new long[count];
			this.count = count;
			for (int id = 0; id < count; id++) last_iowait[id] = last_idle[id] = last_total[id] = 0;
			try {
				stat = new RandomAccessFile("/proc/stat", "r");
			} catch (Throwable ignore) {
				stat = null;
			}
		}

		void sample() {
			if (stat == null) return;
			try {
				stat.seek(0);
				List<String> lines = new ArrayList<>();
				for (int i = 0; i <= count; i++) {
					String line = stat.readLine();
					if (line.startsWith("cpu"))
						lines.add(line);
					else break;
				}
				for (String line : lines) {
					int pos = 0, id;
					String reads[] = line.trim().split(" ");
					if (reads[0].length() > 3) {
						id = Integer.parseInt(reads[0].substring(3));
					} else id = -1;
					long total = 0, iowait = 0, idle = 0, read;
					for (int i = 1; i < reads.length; i++) {
						String v = reads[i];
						if (v.length() <= 0) continue;
						total += read = Long.parseLong(v);
						if (pos == 3) idle = read;
						if (pos == 4) iowait = read;
						pos++;
					}
					if (id < 0) {
						cpu_all_iowait = iowait - last_cpu_all_iowait;
						cpu_all_idle = idle - last_cpu_all_idle;
						cpu_all_total = total - last_cpu_all_total;
						if (cpu_all_total < 0 || cpu_all_idle < 0 || cpu_all_iowait < 0)
							cpu_all_total = cpu_all_idle = cpu_all_iowait = 0;
						last_cpu_all_iowait = iowait;
						last_cpu_all_idle = idle;
						last_cpu_all_total = total;
					} else {
						cpu_iowait[id] = iowait - last_iowait[id];

						cpu_idle[id] = idle - last_idle[id];
						cpu_total[id] = total - last_total[id];
						if (cpu_total[id] < 0 || cpu_idle[id] < 0 || cpu_iowait[id] < 0)
							cpu_total[id] = cpu_idle[id] = cpu_iowait[id] = 0;
						last_iowait[id] = iowait;
						last_idle[id] = idle;
						last_total[id] = total;
					}
				}
			} catch (IOException e) {
				stat = null;
			}
		}

		double getCoreUtil(int id, double freq) {
			double util;
			if (freq > 0) {
				util = 1.0 - (double) (cpu_idle[id] + cpu_iowait[id]) / cpu_total[id];
				if (util < 0) util = 0;
				else if (util > 1) util = 1;
			} else util = 0;
			freq_all += freq;
			return util;
		}

		@Override
		public void generateHtml(StringBuilder out) {
			double idle = (double) cpu_all_idle / cpu_all_total;
			if (idle < 0) idle = 0;
			else if (idle > 1) idle = 1;
			double iowait = (double) cpu_all_iowait / cpu_all_total;
			if (iowait < 0) iowait = 0;
			else if (iowait > 1) iowait = 1;
			double busy = 1.0 - (idle + iowait);
			if (busy < 0) busy = 0;
			double util = (1.0 - idle) * (freq_all / count);
			out.append(String.format(Locale.getDefault(), "util:%d%% busy:%d%% iowait:%d%%", (int) (util * 100.0 + 0.5), (int) (busy * 100.0 + 0.5), (int) (iowait * 100.0 + 0.5)));
			freq_all = 0;
		}

		@Override
		public boolean hasAny() {
			return stat != null;
		}
	}

	private class SoC implements DeviceNode {

		String node_battery_curr, node_battery_volt;
		boolean hasSocTemp, hasBatteryTemp;

		SoC() {
			Kernel k = Kernel.getInstance();
			node_battery_curr = "/sys/class/power_supply/battery/current_now";
			hasSocTemp = k.hasSocTemperature();
			try {
				k.getSocTemperature();
				hasSocTemp = true;
			} catch (Throwable e) {
				hasSocTemp = false;
			}
			hasBatteryTemp = k.hasBatteryTemperature();
			try {
				k.getBatteryTemperature();
				hasBatteryTemp = true;
			} catch (Throwable e) {
				hasBatteryTemp = false;
			}
			if (k.hasNode(node_battery_curr)) {
				// k.grantRead(node_battery_curr);
				try {
					k.readNode(node_battery_curr);
				} catch (Throwable ignore) {
					node_battery_curr = null;
				}
			}
			if (!(hasBatteryTemp && hasSocTemp && node_battery_curr != null)) {
				node_battery_volt = "/sys/class/power_supply/battery/voltage_now";
				if (k.hasNode(node_battery_volt)) {
					// k.grantRead(node_battery_volt);
					try {
						k.readNode(node_battery_volt);
					} catch (Throwable ignore) {
						node_battery_volt = null;
					}
				}
			} else {
				node_battery_volt = null;
			}
		}

		@Override
		public void generateHtml(StringBuilder out) {
			Kernel k = Kernel.getInstance();
			if (hasSocTemp) {
				out.append("SoC:");
				out.append(k.getSocTemperature());
				out.append("℃ ");
			}
			if (hasBatteryTemp) {
				out.append("Batt:");
				out.append(k.getBatteryTemperature());
				out.append("℃ ");
			}
			if (node_battery_curr != null) {
				try {
					out.append(getMicroAmpere(k.readNode(node_battery_curr)));
					out.append("mA ");
				} catch (Throwable ignore) {
					node_battery_curr = null;
				}
			}
			if (node_battery_volt != null) {
				try {
					out.append(getMicroAmpere(k.readNode(node_battery_volt)));
					out.append("mV ");
				} catch (Throwable ignore) {
					node_battery_curr = null;
				}
			}
		}

		@Override
		public boolean hasAny() {
			return hasSocTemp || hasBatteryTemp ||
					node_battery_curr != null ||
					node_battery_volt != null;
		}

		// Not a good method
		private int getMicroAmpere(String read) {
			int raw = Integer.parseInt(read);
			double divider = 1000.0;
			if (raw >= 10000000 || raw <= -10000000) {
				divider = 1000000.0;
			}
			return (int) (raw / divider);
		}
	}

	private class GPU implements DeviceNode {

		String gpu_freq, governor;
		boolean gpu_temp;

		GPU() {
			Kernel k = Kernel.getInstance();
			gpu_freq = "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq";
			if (!k.hasNode(gpu_freq)) {
				gpu_freq = "/sys/class/kgsl/kgsl-3d0/gpuclk";
				if (!k.hasNode(gpu_freq))
					gpu_freq = null;
			}
			if (gpu_freq != null) {
				// k.grantRead(gpu_freq);
				try {
					k.readNode(gpu_freq);
				} catch (Throwable ignore) {
					gpu_freq = null;
				}
			}
			governor = "/sys/class/kgsl/kgsl-3d0/devfreq/governor";
			if (!k.hasNode(governor))
				governor = null;
			if (governor != null) {
				// k.grantRead(governor);
				try {
					k.readNode(governor);
				} catch (Throwable ignore) {
					governor = null;
				}
			}
			gpu_temp = k.hasGpuTemperature();
			try {
				k.getGpuTemperature();
			} catch (Throwable ignore) {
				gpu_temp = false;
			}
		}

		@Override
		public boolean hasAny() {
			return gpu_freq != null || governor != null || gpu_temp;
		}

		@Override
		public void generateHtml(StringBuilder out) {
			Kernel k = Kernel.getInstance();
			out.append("gpu: ");
			if (k.hasGpuTemperature()) {
				out.append(k.getGpuTemperature());
				out.append("℃ ");
			}
			if (governor != null) {
				try {
					out.append(k.readNode(governor));
					out.append(":");
				} catch (Throwable e) {
					governor = null;
				}
			}
			if (gpu_freq != null) {
				try {
					long rawFreq = Integer.parseInt(k.readNode(gpu_freq));
					for (int i = 0; i < 2; i++)
						if (rawFreq > 1000) rawFreq /= 1000;
					out.append(rawFreq);
				} catch (Throwable e) {
					gpu_freq = null;
				}
			}
		}
	}

	private class Sensors implements DeviceNode {

		final List<MaxTempReader> sensors = new ArrayList<>();
		final int soc_id;

		Sensors() {
			String path;
			Kernel k = Kernel.getInstance();
			soc_id = k.getSocRawID();
			int i = 0;
			while (k.hasNode(path = k.getThermalZone(i))) {
				try {
					sensors.add(new MaxTempReader(k, path));
				} catch (FileNotFoundException ignore) {
				}
				i++;
			}
		}

		@Override
		public boolean hasAny() {
			return sensors.size() > 0;
		}

		@Override
		public void generateHtml(StringBuilder out) throws IOException {
			int i = 0;
			for (MaxTempReader reader : sensors) {
				out.append(i);
				out.append(":");
				out.append(reader.read());
				i++;
				if (i % 5 == 0)
					out.append("<br/>");
				else
					out.append(" ");
			}
			if (i % 5 != 0)
				out.append("<br/>");
			out.append("model:");
			out.append(Build.MODEL);
			out.append(" soc_id:");
			out.append(soc_id);
		}
	}

	private class CPU implements DeviceNode {
		final int id;
		final Kernel.CpuCore core;
		final CpuStat stat;

		int curFreq, maxFreq;

		CPU(int id, CpuStat stat) {
			this.id = id;
			this.stat = stat;
			core = Kernel.getInstance().cpuCores.get(id);
			try {
				maxFreq = core.getMaxFrequency();
			} catch (Throwable ex) {
				maxFreq = 1;
			}
		}

		public void generateHtml(StringBuilder out) {
			boolean on = core.isOnline();
			if (on) out.append("<font color=\"#00ff00\">");
			else out.append("<font color=\"#ff0000\">");
			out.append("cpu");
			out.append(id);
			out.append(": ");
			if (core.hasTemperature()) {
				try {
					out.append(core.getTemperature());
					out.append("℃ ");
				} catch (IOException ignore) {
				}
			}
			generateGovernorAndFrequency(on, out);
			out.append("</font>");
		}

		@Override
		public boolean hasAny() {
			return true;
		}

		void generateGovernorAndFrequency(boolean on, StringBuilder out) {
			if (on) {
				try {
					out.append(core.getGovernor());
				} catch (Throwable e) {
					out.append("unknown");
				}
				out.append(":");
				try {
					curFreq = core.getScalingCurrentFrequency();
					if (maxFreq < curFreq) maxFreq = curFreq;
					out.append(curFreq / 1000);
					out.append(" ");
				} catch (Throwable e) {
					curFreq = 0;
					out.append("0 ");
				}
				out.append((int) (stat.getCoreUtil(id, (double) curFreq / maxFreq) * 100.0 + 0.5));
			} else {
				out.append("offline:0 0");
			}
			out.append("%");
		}
	}
}
