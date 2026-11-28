package com.focus.app;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

public class FocusMdnsHelper {
    private static final String TAG = "FocusMdns";
    private static final String MDNS_GROUP = "224.0.0.251";
    private static final int MDNS_PORT = 5353;

    private final Context context;
    private NsdManager nsdManager;
    private NsdManager.RegistrationListener nsdListener;
    private WifiManager.MulticastLock multicastLock;
    private Thread responderThread;
    private MulticastSocket responderSocket;
    private volatile boolean running = false;

    public FocusMdnsHelper(Context context) {
        this.context = context.getApplicationContext();
    }

    public synchronized void start(int webPort) {
        if (running) return;
        running = true;

        acquireMulticastLock();
        registerNsd(webPort);
        startCustomMdnsResponder();
    }

    private void acquireMulticastLock() {
        try {
            WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                multicastLock = wm.createMulticastLock("focus_multicast_lock");
                multicastLock.setReferenceCounted(true);
                multicastLock.acquire();
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to acquire multicast lock", e);
        }
    }

    private void registerNsd(int webPort) {
        try {
            nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
            if (nsdManager == null) return;

            NsdServiceInfo serviceInfo = new NsdServiceInfo();
            serviceInfo.setServiceName("Focus");
            serviceInfo.setServiceType("_http._tcp.");
            serviceInfo.setPort(webPort);

            nsdListener = new NsdManager.RegistrationListener() {
                @Override
                public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                    Log.w(TAG, "NSD registration failed: " + errorCode);
                }

                @Override
                public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                    Log.w(TAG, "NSD unregistration failed: " + errorCode);
                }

                @Override
                public void onServiceRegistered(NsdServiceInfo serviceInfo) {
                    Log.i(TAG, "NSD service registered: " + serviceInfo.getServiceName());
                }

                @Override
                public void onServiceUnregistered(NsdServiceInfo serviceInfo) {
                    Log.i(TAG, "NSD service unregistered");
                }
            };

            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, nsdListener);
        } catch (Exception e) {
            Log.w(TAG, "Failed to register NSD service", e);
        }
    }

    private void startCustomMdnsResponder() {
        responderThread = new Thread(() -> {
            try {
                InetAddress group = InetAddress.getByName(MDNS_GROUP);
                responderSocket = new MulticastSocket(MDNS_PORT);
                responderSocket.setReuseAddress(true);
                responderSocket.setTimeToLive(255);
                responderSocket.joinGroup(group);

                byte[] buffer = new byte[1500];
                while (running) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    responderSocket.receive(packet);

                    String payload = new String(packet.getData(), 0, packet.getLength()).toLowerCase();
                    if (payload.contains("focus") && payload.contains("local")) {
                        String localIp = getLocalIpAddress();
                        if (!"127.0.0.1".equals(localIp)) {
                            byte[] responseData = buildDnsResponse(packet.getData(), packet.getLength(), localIp);
                            if (responseData != null) {
                                DatagramPacket respPacket = new DatagramPacket(
                                        responseData, responseData.length, group, MDNS_PORT
                                );
                                responderSocket.send(respPacket);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                if (running) {
                    Log.w(TAG, "Custom mDNS responder stopped or port busy: " + e.getMessage());
                }
            }
        }, "FocusMdnsResponder");
        responderThread.setDaemon(true);
        responderThread.start();
    }

    private byte[] buildDnsResponse(byte[] queryData, int length, String ipStr) {
        try {
            int queryId = 0;
            if (length >= 2) {
                queryId = ((queryData[0] & 0xFF) << 8) | (queryData[1] & 0xFF);
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            baos.write((queryId >> 8) & 0xFF);
            baos.write(queryId & 0xFF);
            baos.write(0x84);
            baos.write(0x00);
            baos.write(0x00); baos.write(0x01);
            baos.write(0x00); baos.write(0x01);
            baos.write(0x00); baos.write(0x00);
            baos.write(0x00); baos.write(0x00);

            baos.write(5);
            baos.write("focus".getBytes("US-ASCII"));
            baos.write(5);
            baos.write("local".getBytes("US-ASCII"));
            baos.write(0);
            baos.write(0x00); baos.write(0x01);
            baos.write(0x00); baos.write(0x01);

            baos.write(0xC0);
            baos.write(0x0C);
            baos.write(0x00); baos.write(0x01);
            baos.write(0x80); baos.write(0x01);
            baos.write(0x00); baos.write(0x00); baos.write(0x00); baos.write(120);
            baos.write(0x00); baos.write(0x04);

            String[] parts = ipStr.split("\.");
            for (String p : parts) {
                baos.write(Integer.parseInt(p));
            }

            return baos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    public static String getLocalIpAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                if (intf.isLoopback() || !intf.isUp()) continue;
                String name = intf.getName().toLowerCase();
                if (name.contains("wlan") || name.contains("ap") || name.contains("eth")) {
                    List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                    for (InetAddress addr : addrs) {
                        if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                            return addr.getHostAddress();
                        }
                    }
                }
            }
            for (NetworkInterface intf : interfaces) {
                if (intf.isLoopback() || !intf.isUp()) continue;
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }

    public synchronized void stop() {
        running = false;
        if (nsdManager != null && nsdListener != null) {
            try {
                nsdManager.unregisterService(nsdListener);
            } catch (Exception ignored) {}
            nsdListener = null;
        }
        if (responderSocket != null) {
            try {
                responderSocket.close();
            } catch (Exception ignored) {}
            responderSocket = null;
        }
        if (multicastLock != null && multicastLock.isHeld()) {
            try {
                multicastLock.release();
            } catch (Exception ignored) {}
            multicastLock = null;
        }
    }
}
