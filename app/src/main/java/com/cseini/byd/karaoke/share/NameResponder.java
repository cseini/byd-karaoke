package com.cseini.byd.karaoke.share;

import android.content.Context;
import android.net.wifi.WifiManager;

import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

// 앱이 자기 이름(예: byd-player.local)으로 여는 mDNS 응답기 — 같은 망의 폰·태블릿이 이 이름을 멀티캐스트로 물으면 지금 이 기기의 IP(IPv4·IPv6)로 대답한다.
//   IP 가 바뀌어도 홈 화면에 추가한 주소는 그대로다. 릴레이(MdnsResponder)와 같은 구현.
final class NameResponder {
    private final String HOST;
    private final Context ctx;
    private final Supplier<List<String>> ips;
    private final Consumer<String> log;
    private volatile boolean run;
    private MulticastSocket sock;
    private WifiManager.MulticastLock lock;
    private int answered = 0, seen = 0, pkts = 0;
    private long lastPkt = 0;

    NameResponder(Context ctx, String host, Supplier<List<String>> ips, Consumer<String> log) { this.ctx = ctx; this.HOST = host; this.ips = ips; this.log = log; }

    void start() {
        if (run) return;
        run = true;
        Thread t = new Thread(this::loop, "mdns-resp");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        run = false;
        try { if (sock != null) sock.close(); } catch (Throwable ignored) {}
        try { if (lock != null && lock.isHeld()) lock.release(); } catch (Throwable ignored) {}
    }

    private void loop() {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) { lock = wm.createMulticastLock("byd-mdns"); lock.setReferenceCounted(false); lock.acquire(); }
            sock = new MulticastSocket(null);
            sock.setReuseAddress(true);
            sock.bind(new InetSocketAddress(5353));
            InetAddress grp = InetAddress.getByName("224.0.0.251");
            sock.setSoTimeout(20_000);   // 20초마다 깨어나 새로 생긴 인터페이스(핫스팟·와이파이가 시동 뒤에 붙는 경우)에도 가입한다
            int joined = join(grp);
            log.accept("이름 주소 " + HOST + " — mDNS 응답 시작(가입 인터페이스 " + joined + "개)");
            long lastLog = System.currentTimeMillis();
            byte[] buf = new byte[1500];
            while (run) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try { sock.receive(p); } catch (java.net.SocketTimeoutException te) { join(grp); continue; }
                if (p.getLength() >= 12 && (buf[2] & 0x80) == 0) { pkts++; if (pkts == 1 || System.currentTimeMillis() - lastPkt > 60_000) { lastPkt = System.currentTimeMillis(); log.accept("이름 주소 — mDNS 질문 패킷 누적 " + pkts + "건(우리 이름 " + seen + "건) · 보낸 곳 " + p.getAddress().getHostAddress()); } }
                int kind = question(buf, p.getLength());
                if (kind == 0) continue;
                seen++;
                final boolean legacy = p.getPort() != 5353;   // 임시 포트에서 온 질문(안드로이드 등) = 레거시 유니캐스트 — ID·질문을 되돌려 쓴 응답을 보낸 쪽에 직접 보내야 받아 준다
                byte[] rsp = legacy ? answer(qId, java.util.Arrays.copyOfRange(buf, 12, qEnd), qCnt, true) : answer(0, null, 0, false);
                if (rsp == null) continue;
                if (legacy || p.getAddress() instanceof java.net.Inet6Address) sock.send(new DatagramPacket(rsp, rsp.length, p.getAddress(), p.getPort()));   // IPv6로 온 질문은 보낸 쪽(링크로컬 범위 포함)에 직접 답한다
                else sock.send(new DatagramPacket(rsp, rsp.length, grp, 5353));
                answered++;
                if (answered == 1 || System.currentTimeMillis() - lastLog > 60_000) { lastLog = System.currentTimeMillis(); log.accept("이름 주소 " + HOST + " 질문 " + seen + "건 중 " + answered + "건 응답 · 마지막 " + p.getAddress().getHostAddress()); }
            }
        } catch (Throwable e) {
            if (run) log.accept("이름 주소 응답기 중단: " + e);
        } finally {
            run = false;
            try { if (sock != null) sock.close(); } catch (Throwable ignored) {}
            try { if (lock != null && lock.isHeld()) lock.release(); } catch (Throwable ignored) {}
        }
    }

    private final java.util.Set<String> joinedIf = new java.util.HashSet<>();
    private int join(InetAddress grp) {
        int added = 0;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                try {
                    if (!ni.isUp() || ni.isLoopback() || !ni.supportsMulticast() || joinedIf.contains(ni.getName())) continue;
                    boolean v4 = false;
                    for (InetAddress a : Collections.list(ni.getInetAddresses())) if (a instanceof Inet4Address) v4 = true;
                    if (!v4) continue;
                    sock.joinGroup(new InetSocketAddress(grp, 5353), ni);
                    try { sock.joinGroup(new InetSocketAddress(InetAddress.getByName("ff02::fb"), 5353), ni); } catch (Throwable ignored) {}   // IPv6 mDNS 그룹도 듣는다
                    joinedIf.add(ni.getName());
                    added++;
                    if (answered + seen > 0 || added > 0) log.accept("이름 주소 " + HOST + " — " + ni.getName() + " 인터페이스 가입");
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return added;
    }

    // 이름 읽기(압축 포인터 따라가기) — 한 패킷에 질문이 여럿이면 뒤 질문은 앞 이름을 가리키는 포인터(0xC0..)로 온다. 끝 위치를 end[0]에 돌려준다. 이상하면 null.
    private static String readName(byte[] b, int n, int o, int[] end) {
        StringBuilder name = new StringBuilder();
        int jumps = 0; boolean jumped = false;
        while (true) {
            if (o >= n) return null;
            int l = b[o++] & 0xff;
            if (l == 0) break;
            if ((l & 0xC0) == 0xC0) {
                if (o >= n || ++jumps > 8) return null;
                int ptr = ((l & 0x3F) << 8) | (b[o++] & 0xff);
                if (!jumped) { end[0] = o; jumped = true; }
                if (ptr >= n) return null;
                o = ptr;
                continue;
            }
            if ((l & 0xC0) != 0 || o + l > n) return null;
            if (name.length() > 0) name.append('.');
            name.append(new String(b, o, l, java.nio.charset.StandardCharsets.UTF_8));
            o += l;
        }
        if (!jumped) end[0] = o;
        return name.toString();
    }

    private final java.util.Set<String> loggedQ = new java.util.HashSet<>();
    private int qEnd = 12, qCnt = 0, qId = 0;   // 마지막으로 해석한 질문 패킷의 질문 구역 끝·질문 수·ID(레거시 유니캐스트 응답에 되돌려 쓴다)

    // 질문 중에 우리 이름의 A(1)·AAAA(28)·ANY(255) 질문이 있으면 1. 응답 패킷(QR 비트)이면 0. 처음 보는 (이름,종류)는 30개까지 로그로 남긴다.
    private int question(byte[] b, int n) {
        if (n < 12 || (b[2] & 0x80) != 0) return 0;
        int qd = ((b[4] & 0xff) << 8) | (b[5] & 0xff), o = 12, hit = 0;
        int[] end = new int[1];
        qId = ((b[0] & 0xff) << 8) | (b[1] & 0xff); qCnt = 0; qEnd = 12;
        for (int q = 0; q < qd; q++) {
            String name = readName(b, n, o, end);
            if (name == null) break;
            o = end[0];
            if (o + 4 > n) break;
            int type = ((b[o] & 0xff) << 8) | (b[o + 1] & 0xff);
            o += 4; qCnt = q + 1; qEnd = o;
            String key = name + "/" + type;
            if (loggedQ.size() < 30 && loggedQ.add(key)) log.accept("이름 주소 — 질문 이름 " + name + " 종류 " + type);
            if (name.equalsIgnoreCase(HOST) && (type == 1 || type == 28 || type == 255)) hit = 1;
        }
        return hit;
    }

    private byte[] answer(int id, byte[] qSec, int qCount, boolean legacy) {
        List<byte[]> addrs = new ArrayList<>();
        for (String s : ips.get()) {
            String[] q = s.split("\\.");
            if (q.length != 4) continue;
            try { addrs.add(new byte[]{(byte) Integer.parseInt(q[0]), (byte) Integer.parseInt(q[1]), (byte) Integer.parseInt(q[2]), (byte) Integer.parseInt(q[3])}); } catch (NumberFormatException ignored) {}
        }
        List<byte[]> a6 = new ArrayList<>();   // 와이파이·핫스팟 인터페이스의 IPv6(전역·링크로컬) — 셀룰러(rmnet)는 뺀다
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                String nm = ni.getName();
                if (!ni.isUp() || ni.isLoopback() || !(nm.startsWith("wlan") || nm.startsWith("ap") || nm.startsWith("swlan"))) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) if (a instanceof java.net.Inet6Address && !a.isLoopbackAddress()) a6.add(a.getAddress());
            }
        } catch (Throwable ignored) {}
        if (addrs.isEmpty() && a6.isEmpty()) return null;
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        int an = addrs.size() + a6.size(), ar = a6.isEmpty() ? 1 : 0;
        o.write(id >> 8); o.write(id & 0xff); o.write(0x84); o.write(0);   // 응답+권위 (일반=id 0, 레거시=질문 ID 그대로)
        o.write(0); o.write(legacy ? qCount : 0); o.write(an >> 8); o.write(an & 0xff); o.write(0); o.write(0); o.write(0); o.write(ar);
        if (legacy && qSec != null) o.write(qSec, 0, qSec.length);   // 레거시는 질문 구역을 그대로 되돌린다(질문 안의 압축 포인터도 같은 위치라 유효)
        for (byte[] a : addrs) rr(o, 1, a, legacy);
        for (byte[] a : a6) rr(o, 28, a, legacy);
        if (a6.isEmpty()) {   // IPv6가 없으면 "AAAA 없음"을 알려 아이폰이 기다리지 않게 한다(NSEC: 다음 이름=자기 자신, 있는 종류=A뿐)
            java.io.ByteArrayOutputStream nm = new java.io.ByteArrayOutputStream(); name(nm);
            byte[] nb = nm.toByteArray();
            o.write(nb, 0, nb.length); o.write(0); o.write(47); o.write(legacy ? 0 : 0x80); o.write(1); o.write(0); o.write(0); o.write(0); o.write(legacy ? 10 : 120);
            int rl = nb.length + 3; o.write(rl >> 8); o.write(rl & 0xff);
            o.write(nb, 0, nb.length); o.write(0); o.write(1); o.write(0x40);
        }
        return o.toByteArray();
    }

    private void name(java.io.ByteArrayOutputStream o) {
        for (String l : HOST.split("\\.")) { o.write(l.length()); byte[] lb = l.getBytes(java.nio.charset.StandardCharsets.UTF_8); o.write(lb, 0, lb.length); }
        o.write(0);
    }

    private void rr(java.io.ByteArrayOutputStream o, int type, byte[] data, boolean legacy) {
        name(o);
        o.write(0); o.write(type);
        o.write(legacy ? 0 : 0x80); o.write(1);       // IN (+ 캐시 비우기 비트 — 레거시 응답엔 쓰지 않는다)
        o.write(0); o.write(0); o.write(0); o.write(legacy ? 10 : 120);   // TTL 120초 (레거시는 10초 이하)
        o.write(0); o.write(data.length);
        o.write(data, 0, data.length);
    }
}
