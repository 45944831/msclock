package com.wb.msclock;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DNS 交叉诊断（失败路径专用旁路，不在成功路径上）。
 *
 * 视角定义：
 *   本机侧 = InetAddress.getAllByName，等价于「本机 hosts 文件 + 系统 DNS」的合并结果；
 *   权威侧 = 直连公共 DNS 服务器 IP 走 UDP 53，绕过本机 hosts 与本机 DNS。
 *
 * 为什么要两边都看：
 *   v1.4 只能说「域名解析失败」，无法区分是本机 DNS/hosts 被污染，还是域名本身出问题。
 *   权威能解析而本机解析不了 → 明确是本机 DNS/hosts 故障；
 *   权威也查不到 → 域名本身无记录；
 *   权威查询超时 → 无结论，保持原文案（绝不拿同样失败的探针给失败背书）。
 *
 * 为什么不比 IP 字符串：
 *   校时源全是 CDN 域名，本机运营商 DNS 与公共 DNS 会被调度到不同边缘节点，
 *   IP 不同属正常。判定只用「能否解析」两个布尔量，从根上避开这个误报源。
 *
 * 本类不依赖 Android SDK，可在纯 JDK 下编译和单测（见 tests/jvm/DnsProbeTest.java）。
 */
public final class DnsProbe {

    private DnsProbe() {}

    /** 诊断结论 */
    public static final int NONE = 0;          // 无结论，保持 v1.4 原文案
    public static final int LOCAL_DNS = 1;     // 公网可解析但本机解析失败 → 本机 DNS/hosts 故障
    public static final int REMOTE_MISS = 2;   // 公网权威也查不到该域名 → 域名无解析记录

    /** 权威查询状态 */
    public static final int REMOTE_OK = 1;     // 有 A 记录
    public static final int REMOTE_NODATA = 0; // NXDOMAIN / 无 A 记录
    public static final int REMOTE_ERROR = -1; // 超时 / 无响应 / 报文无效

    /** 公共 DNS 服务器，写死 IP：避免诊断自身依赖本机 DNS（那正是被怀疑的对象） */
    public static final String[] SERVERS = {"223.5.5.5", "119.29.29.29"};

    /**
     * 纯判定函数：本机是否能解析 + 权威查询状态 → 结论。
     * 无结论的情况一律返回 NONE，由调用方保留原文案。
     */
    public static int judge(boolean localOk, int remoteState) {
        if (localOk) return NONE;                  // 本机解析正常，失败与 DNS 无关
        if (remoteState == REMOTE_OK) return LOCAL_DNS;
        if (remoteState == REMOTE_NODATA) return REMOTE_MISS;
        return NONE;                               // 权威探不到 → 不给结论
    }

    /** 把结论翻译成可操作文案；NONE 返回 null，表示沿用原分级文案 */
    public static String textOf(int verdict) {
        switch (verdict) {
            case LOCAL_DNS: return "域名解析失败 · 本机DNS/hosts异常";
            case REMOTE_MISS: return "域名解析失败 · 公网无此记录";
            default: return null;
        }
    }

    /** 诊断结果载体 */
    public static final class Diagnosis {
        public int verdict = NONE;
        public boolean localOk;
        public int remoteState = REMOTE_ERROR;
        public String domain;
        public String text;      // null = 保持原文案
    }

    /** 完整诊断：本机解析 + 权威解析 + 判定，全部带硬超时 */
    public static Diagnosis run(String domain, int timeoutMs) {
        Diagnosis d = new Diagnosis();
        d.domain = domain;
        d.localOk = resolveLocal(domain, timeoutMs);
        d.remoteState = queryState(domain, SERVERS, timeoutMs);
        d.verdict = judge(d.localOk, d.remoteState);
        d.text = textOf(d.verdict);
        return d;
    }

    // ------------------------------------------------------------ 本机侧

    /**
     * 本机解析带硬超时。InetAddress.getByName 无超时参数，被劫持的 DNS 可能挂几十秒，
     * 这里用 daemon 线程 + latch 限时；超时按「解析失败」处理。
     */
    public static boolean resolveLocal(String domain, int timeoutMs) {
        final AtomicBoolean ok = new AtomicBoolean(false);
        final CountDownLatch done = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            try {
                ok.set(InetAddress.getAllByName(domain).length > 0);
            } catch (Throwable ignored) {
                ok.set(false);
            } finally {
                done.countDown();
            }
        }, "dnsprobe-local");
        t.setDaemon(true);
        t.start();
        try {
            done.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return ok.get();
    }

    // ------------------------------------------------------------ 权威侧

    /**
     * 依次查询若干公共 DNS，任一返回有效响应即定论。
     * 注意：NXDOMAIN(rcode=3) 才是「域名不存在」；SERVFAIL 等算 ERROR，避免误判域名挂了。
     */
    public static int queryState(String domain, String[] servers, int timeoutMs) {
        boolean anyResponse = false;
        for (String srv : servers) {
            try {
                int id = (int) (Math.random() * 65535) & 0xffff;
                byte[] query = buildQuery(domain, id);
                InetAddress addr = InetAddress.getByName(srv);   // 字面量 IP，不触发 DNS
                DatagramSocket sock = new DatagramSocket();
                try {
                    sock.setSoTimeout(timeoutMs);
                    sock.send(new DatagramPacket(query, query.length, addr, 53));
                    byte[] buf = new byte[2048];
                    DatagramPacket in = new DatagramPacket(buf, buf.length);
                    sock.receive(in);
                    if (in.getLength() < 12) continue;
                    int rid = ((buf[0] & 0xff) << 8) | (buf[1] & 0xff);
                    if (rid != id) continue;
                    anyResponse = true;
                    int rcode = buf[3] & 0x0f;
                    if (rcode == 3) return REMOTE_NODATA;         // NXDOMAIN
                    if (rcode != 0) continue;                     // SERVFAIL 等：换下一个服务器
                    List<String> ips = parseARecords(buf, in.getLength());
                    if (!ips.isEmpty()) return REMOTE_OK;
                } finally {
                    sock.close();
                }
            } catch (Throwable ignored) {
                // 超时 / 端口不可达：换下一个服务器
            }
        }
        return anyResponse ? REMOTE_NODATA : REMOTE_ERROR;
    }

    // ------------------------------------------------------------ DNS 报文（纯函数，可单测）

    /** 构造 A 记录查询报文：12 字节头 + QNAME + QTYPE(A=1) + QCLASS(IN=1) */
    public static byte[] buildQuery(String domain, int id) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write((id >> 8) & 0xff);
        b.write(id & 0xff);
        b.write(0x01); b.write(0x00);              // RD=1
        b.write(0x00); b.write(0x01);              // QDCOUNT=1
        b.write(0x00); b.write(0x00);              // ANCOUNT
        b.write(0x00); b.write(0x00);              // NSCOUNT
        b.write(0x00); b.write(0x00);              // ARCOUNT
        for (String label : domain.split("\\.")) {
            if (label.isEmpty()) continue;
            byte[] lb = label.getBytes(StandardCharsets.US_ASCII);
            b.write(lb.length & 0xff);
            b.write(lb, 0, lb.length);
        }
        b.write(0x00);                             // QNAME 结束
        b.write(0x00); b.write(0x01);              // QTYPE=A
        b.write(0x00); b.write(0x01);              // QCLASS=IN
        return b.toByteArray();
    }

    /** 跳过一个 DNS name（含 0xC0 压缩指针），返回下一个字段的偏移 */
    public static int skipName(byte[] p, int off) {
        int i = off;
        while (i < p.length) {
            int len = p[i] & 0xff;
            if ((len & 0xC0) == 0xC0) return i + 2;
            if (len == 0) return i + 1;
            i += 1 + len;
        }
        return i;
    }

    /** 从响应报文中提取所有 A 记录（点分十进制） */
    public static List<String> parseARecords(byte[] p, int len) {
        List<String> out = new ArrayList<>();
        if (len < 12) return out;
        int qd = ((p[4] & 0xff) << 8) | (p[5] & 0xff);
        int an = ((p[6] & 0xff) << 8) | (p[7] & 0xff);
        int rcode = p[3] & 0x0f;
        if (rcode != 0) return out;
        int i = 12;
        for (int q = 0; q < qd && i < len; q++) {
            i = skipName(p, i) + 4;                // QTYPE + QCLASS
        }
        for (int a = 0; a < an && i + 10 <= len; a++) {
            i = skipName(p, i);
            if (i + 10 > len) break;
            int type = ((p[i] & 0xff) << 8) | (p[i + 1] & 0xff);
            int cls = ((p[i + 2] & 0xff) << 8) | (p[i + 3] & 0xff);
            int rdlen = ((p[i + 8] & 0xff) << 8) | (p[i + 9] & 0xff);
            i += 10;                               // TYPE + CLASS + TTL + RDLENGTH
            if (type == 1 && cls == 1 && rdlen == 4 && i + 4 <= len) {
                out.add((p[i] & 0xff) + "." + (p[i + 1] & 0xff) + "."
                        + (p[i + 2] & 0xff) + "." + (p[i + 3] & 0xff));
            }
            i += rdlen;
        }
        return out;
    }
}
