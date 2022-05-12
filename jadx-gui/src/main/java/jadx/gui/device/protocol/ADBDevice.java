package jadx.gui.device.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.reactivex.annotations.NonNull;

import jadx.core.utils.StringUtils;
import jadx.core.utils.log.LogUtils;
import jadx.gui.device.protocol.ADB.JDWPProcessListener;
import jadx.gui.device.protocol.ADB.Process;

public class ADBDevice {
	private static final Logger LOG = LoggerFactory.getLogger(ADBDevice.class);

	private static final String CMD_TRACK_JDWP = "000atrack-jdwp";

	ADBDeviceInfo info;
	String androidReleaseVer;
	volatile Socket jdwpListenerSock;

	public ADBDevice(ADBDeviceInfo info) {
		this.info = info;
	}

	public ADBDeviceInfo getDeviceInfo() {
		return info;
	}

	public boolean updateDeviceInfo(ADBDeviceInfo info) {
		boolean matched = this.info.serial.equals(info.serial);
		if (matched) {
			this.info = info;
		}
		return matched;
	}

	public String getSerial() {
		return info.serial;
	}

	public boolean removeForward(String localPort) throws IOException {
		return ADB.removeForward(info.adbHost, info.adbPort, info.serial, localPort);
	}

	public ForwardResult forwardJDWP(String localPort, String jdwpPid) throws IOException {
		try (Socket socket = ADB.connect(info.adbHost, info.adbPort)) {
			String cmd = String.format("host:forward:tcp:%s;jdwp:%s", localPort, jdwpPid);
			cmd = String.format("%04x%s", cmd.length(), cmd);
			InputStream inputStream = socket.getInputStream();
			OutputStream outputStream = socket.getOutputStream();
			ForwardResult rst;
			if (ADB.setSerial(info.serial, outputStream, inputStream)) {
				outputStream.write(cmd.getBytes());
				if (!ADB.isOkay(inputStream)) {
					rst = new ForwardResult(1, ADB.readServiceProtocol(inputStream));
				} else if (!ADB.isOkay(inputStream)) {
					rst = new ForwardResult(2, ADB.readServiceProtocol(inputStream));
				} else {
					rst = new ForwardResult(0, null);
				}
			} else {
				rst = new ForwardResult(1, "Unknown error.".getBytes());
			}
			return rst;
		}
	}

	public static class ForwardResult {
		/**
		 * 0 for success, 1 for failed at binding to local tcp, 2 for failed at remote.
		 */
		public int state;
		public String desc;

		public ForwardResult(int state, byte[] desc) {
			if (desc != null) {
				this.desc = new String(desc);
			} else {
				this.desc = "";
			}
			this.state = state;
		}
	}

	/**
	 * @return pid otherwise -1
	 */
	public int launchApp(String fullAppName) throws IOException, InterruptedException {
		byte[] res;
		try (Socket socket = ADB.connect(info.adbHost, info.adbPort)) {
			String cmd = "am start -D -n " + fullAppName;
			res = ADB.execShellCommandRaw(info.serial, cmd, socket.getOutputStream(), socket.getInputStream());
			if (res == null) {
				return -1;
			}
		}
		String rst = new String(res).trim();
		if (rst.startsWith("Starting: Intent {") && rst.endsWith(fullAppName + " }")) {
			Thread.sleep(40);
			String pkg = fullAppName.split("/")[0];
			for (Process process : getProcessByPkg(pkg)) {
				return Integer.parseInt(process.pid);
			}
		}
		return -1;
	}

	public String getAndroidReleaseVersion() {
		if (!StringUtils.isEmpty(androidReleaseVer)) {
			return androidReleaseVer;
		}
		try {
			List<String> list = getProp("ro.build.version.release");
			if (list.size() != 0) {
				androidReleaseVer = list.get(0);
			}
		} catch (Exception e) {
			LOG.error("Failed to get android release version", e);
			androidReleaseVer = "";
		}
		return androidReleaseVer;
	}

	public List<String> getProp(String entry) throws IOException {
		try (Socket socket = ADB.connect(info.adbHost, info.adbPort)) {
			List<String> props = Collections.emptyList();
			String cmd = "getprop";
			if (!StringUtils.isEmpty(entry)) {
				cmd += " " + entry;
			}
			byte[] payload = ADB.execShellCommandRaw(info.serial, cmd,
					socket.getOutputStream(), socket.getInputStream());
			if (payload != null) {
				props = new ArrayList<>();
				String[] lines = new String(payload).split("\n");
				for (String line : lines) {
					line = line.trim();
					if (!line.isEmpty()) {
						props.add(line);
					}
				}
			}
			return props;
		}
	}

	public List<Process> getProcessByPkg(String pkg) throws IOException {
		return getProcessList("ps | grep " + pkg, 0);
	}

	@NonNull
	public List<Process> getProcessList() throws IOException {
		return getProcessList("ps", 1);
	}

	private List<Process> getProcessList(String cmd, int index) throws IOException {
		try (Socket socket = ADB.connect(info.adbHost, info.adbPort)) {
			List<Process> procs = new ArrayList<>();
			byte[] payload = ADB.execShellCommandRaw(info.serial, cmd,
					socket.getOutputStream(), socket.getInputStream());
			if (payload != null) {
				String ps = new String(payload);
				String[] psLines = ps.split("\n");
				for (String line : psLines) {
					line = line.trim();
					if (line.isEmpty()) {
						continue;
					}
					Process proc = Process.make(line);
					if (proc != null) {
						procs.add(proc);
					} else {
						LOG.error("Unexpected process info data received: \"{}\"", LogUtils.escape(line));
					}
				}
			}
			return procs;
		}
	}

	public boolean listenForJDWP(JDWPProcessListener listener) throws IOException {
		if (this.jdwpListenerSock != null) {
			return false;
		}
		jdwpListenerSock = ADB.connect(this.info.adbHost, this.info.adbPort);
		InputStream inputStream = jdwpListenerSock.getInputStream();
		OutputStream outputStream = jdwpListenerSock.getOutputStream();
		if (ADB.setSerial(info.serial, outputStream, inputStream)
				&& ADB.execCommandAsync(outputStream, inputStream, CMD_TRACK_JDWP)) {
			Executors.newFixedThreadPool(1).execute(() -> {
				for (;;) {
					byte[] res = ADB.readServiceProtocol(inputStream);
					if (res != null) {
						if (listener != null) {
							String payload = new String(res);
							String[] ids = payload.split("\n");
							Set<String> idList = new HashSet<>(ids.length);
							for (String id : ids) {
								if (!id.trim().isEmpty()) {
									idList.add(id);
								}
							}
							listener.jdwpProcessOccurred(this, idList);
						}
					} else { // socket disconnected
						break;
					}
				}
				if (listener != null) {
					this.jdwpListenerSock = null;
					listener.jdwpListenerClosed(this);
				}
			});
		} else {
			jdwpListenerSock.close();
			jdwpListenerSock = null;
			return false;
		}
		return true;
	}

	public void stopListenForJDWP() {
		if (jdwpListenerSock != null) {
			try {
				jdwpListenerSock.close();
			} catch (Exception e) {
				LOG.error("JDWP socket close failed", e);
			}
		}
		this.jdwpListenerSock = null;
	}

	@Override
	public int hashCode() {
		return info.serial.hashCode();
	}

	@Override
	public boolean equals(Object obj) {
		if (obj instanceof ADBDevice) {
			return ((ADBDevice) obj).getDeviceInfo().serial.equals(info.serial);
		}
		return false;
	}

	@Override
	public String toString() {
		return info.allInfo;
	}
}
