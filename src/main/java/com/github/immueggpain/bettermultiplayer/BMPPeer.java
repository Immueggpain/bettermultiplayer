package com.github.immueggpain.bettermultiplayer;

import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.Charset;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.IOUtils;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(description = "Start BMP client", name = "client", mixinStandardHelpOptions = true, version = Launcher.VERSTR)
public class BMPPeer implements Callable<Void> {

	@Option(names = { "-p", "--port" }, required = true, description = "server's port")
	public int serverPort;
	@Option(names = { "-s", "--server" }, required = true, description = "server's name(ip or domain)")
	public String serverName;

	/** socket communicating with ovpn */
	private DatagramSocket socketOvpn;
	/** socket communicating with server */
	private DatagramSocket socketServer;

	@Override
	public Void call() throws Exception {
		// check tap device
		if (!hasTapAdapter()) {
			// make sure tap driver/adapter is installed!
			System.out.println("Please intall tap adapter");
			Process process = new ProcessBuilder("ovpn\\tap-windows.exe").inheritIO().start();
			int exitCode = process.waitFor();
			if (exitCode != 0) {
				System.err.println("install failed! exit code: " + exitCode);
				System.err.println("Maybe you should try again?");
				return null;
			} else {
				System.out.println("tap adapter installed ok!");
			}
			// wait a sec
			Thread.sleep(1000);

			// setup udp redirect
			Thread recvOvpnThread = Util.execAsync("recv_ovpn_thread", () -> recv_ovpn_thread(Launcher.LOCAL_PORT));
			Thread recvServerThread = Util.execAsync("recv_server_thread",
					() -> recv_server_thread(Launcher.LOCAL_OVPN_PORT));

			// start ovpn
			startOvpnProcess(Launcher.LOCAL_PORT);

			recvOvpnThread.join();
			recvServerThread.join();
		}
		return null;
	}

	private void recv_ovpn_thread(int listen_port) {
		try {
			// resolve server name to ip
			InetAddress serverIp = InetAddress.getByName(serverName);

			// setup sockets
			InetAddress loopback_addr = InetAddress.getByName("127.0.0.1");
			socketOvpn = new DatagramSocket(listen_port, loopback_addr);

			// setup packet
			byte[] recvBuf = new byte[4096];
			DatagramPacket p = new DatagramPacket(recvBuf, recvBuf.length);

			// recv loop
			while (true) {
				p.setData(recvBuf);
				socketOvpn.receive(p);
				p.setAddress(serverIp);
				p.setPort(serverPort);
				socketServer.send(p);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private void recv_server_thread(int local_ovpn_port) {
		try {
			InetAddress loopback_addr = InetAddress.getByName("127.0.0.1");

			// setup sockets
			InetAddress allbind_addr = InetAddress.getByName("0.0.0.0");
			socketServer = new DatagramSocket(0, allbind_addr);

			// setup packet
			byte[] recvBuf = new byte[4096];
			DatagramPacket p = new DatagramPacket(recvBuf, recvBuf.length);

			// recv loop
			while (true) {
				p.setData(recvBuf);
				socketServer.receive(p);
				p.setAddress(loopback_addr);
				p.setPort(local_ovpn_port);
				socketOvpn.send(p);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private static void startOvpnProcess(int local_listen_port) throws IOException, InterruptedException {
		Process process = new ProcessBuilder("ovpn\\openvpn.exe", "--dev", "tap", "--remote", "127.0.0.1",
				String.valueOf(local_listen_port), "udp").inheritIO().start();
		process.waitFor();
	}

	private static boolean hasTapAdapter() throws IOException, InterruptedException {
		Process process = new ProcessBuilder("ovpn\\openvpn.exe", "--show-adapters").redirectErrorStream(true).start();
		InputStream is = process.getInputStream();
		String output = IOUtils.toString(is, Charset.defaultCharset());
		process.waitFor();
		Pattern checkRegex = Pattern.compile("'.+' \\{.+\\}");
		Matcher m = checkRegex.matcher(output);
		return m.find();
	}

}