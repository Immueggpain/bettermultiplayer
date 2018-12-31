package com.github.immueggpain.bettermultiplayer;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.lang3.ArrayUtils;

import com.github.immueggpain.bettermultiplayer.Launcher.ClientSettings;

public class BMPClient {

	public void run(ClientSettings settings) {
		// send a check udp packet to server
		// server respond, so make sure server is running & aes is correct

		// create sovpn udp socket & cserver udp socket
		// 1 thread recv sovpn, send with cserver to server
		// 1 thread recv cserver, send with sovpn to ovpn
		// start ovpn process
		try {
			// convert password to aes key
			byte[] bytes = settings.password.getBytes(StandardCharsets.UTF_8);
			byte[] byteKey = new byte[16];
			System.arraycopy(bytes, 0, byteKey, 0, Math.min(byteKey.length, bytes.length));
			SecretKeySpec secretKey = new SecretKeySpec(byteKey, "AES");
			// we use 2 ciphers because we want to support encrypt/decrypt full-duplex
			String transformation = "AES/GCM/PKCS5Padding";
			Cipher encrypter = Cipher.getInstance(transformation);
			Cipher decrypter = Cipher.getInstance(transformation);

			// setup sockets
			InetAddress server_addr = InetAddress.getByName(settings.server_ip);
			InetAddress loopback_addr = InetAddress.getByName("127.0.0.1");
			int local_ovpn_port = 1194;
			int local_listen_port = 1195;
			DatagramSocket sovpn_s = new DatagramSocket(local_listen_port, loopback_addr);
			DatagramSocket cserver_s = new DatagramSocket();

			// start working threads
			Thread transfer_c2s_thread = scmt.execAsync("transfer_c2s",
					() -> transfer_c2s(sovpn_s, encrypter, secretKey, server_addr, settings.server_port, cserver_s));
			Thread transfer_s2c_thread = scmt.execAsync("transfer_s2c",
					() -> transfer_s2c(cserver_s, decrypter, secretKey, loopback_addr, local_ovpn_port, sovpn_s));

			// start ovpn
			startOvpnProcess(local_listen_port, settings.tap_ip, settings.tap_mask);

			transfer_c2s_thread.join();
			transfer_s2c_thread.join();
		} catch (Exception e) {
			e.printStackTrace();
		}

	}

	private static void transfer_c2s(DatagramSocket sovpn_s, Cipher encrypter, Key secretKey, InetAddress server_addr,
			int server_port, DatagramSocket cserver_s) {
		try {
			byte[] recvBuf = new byte[4096];
			DatagramPacket p = new DatagramPacket(recvBuf, recvBuf.length);
			while (true) {
				p.setData(recvBuf);
				sovpn_s.receive(p);
				byte[] encrypted = encrypt(encrypter, secretKey, p.getData(), p.getOffset(), p.getLength());
				p.setData(encrypted);
				p.setAddress(server_addr);
				p.setPort(server_port);
				cserver_s.send(p);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private static void transfer_s2c(DatagramSocket cserver_s, Cipher decrypter, Key secretKey,
			InetAddress loopback_addr, int local_ovpn_port, DatagramSocket sovpn_s) {
		try {
			byte[] recvBuf = new byte[4096];
			DatagramPacket p = new DatagramPacket(recvBuf, recvBuf.length);
			while (true) {
				p.setData(recvBuf);
				cserver_s.receive(p);
				byte[] decrypted = decrypt(decrypter, secretKey, p.getData(), p.getOffset(), p.getLength());
				p.setData(decrypted);
				p.setAddress(loopback_addr);
				p.setPort(local_ovpn_port);
				sovpn_s.send(p);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public static byte[] encrypt(Cipher encrypter, Key secretKey, byte[] input, int offset, int length)
			throws GeneralSecurityException {
		// we need init every time because we want random iv
		encrypter.init(Cipher.ENCRYPT_MODE, secretKey);
		byte[] iv = encrypter.getIV();
		byte[] encrypedBytes = encrypter.doFinal(input, offset, length);
		return ArrayUtils.addAll(iv, encrypedBytes);
	}

	public static byte[] decrypt(Cipher decrypter, Key secretKey, byte[] input, int offset, int length)
			throws GeneralSecurityException {
		GCMParameterSpec gcmParameterSpec = new GCMParameterSpec(128, input, offset, 12);
		decrypter.init(Cipher.DECRYPT_MODE, secretKey, gcmParameterSpec);
		byte[] decryptedBytes = decrypter.doFinal(input, offset + 12, length - 12);
		return decryptedBytes;
	}

	private static void startOvpnProcess(int local_listen_port, String tap_ip, String tap_mask)
			throws IOException, InterruptedException {
		Process process = new ProcessBuilder("ovpn\\openvpn.exe", "--dev", "tap", "--remote", "127.0.0.1",
				String.valueOf(local_listen_port), "udp", "--ifconfig", tap_ip, tap_mask).inheritIO().start();
		process.waitFor();
	}

}
