import com.wb.msclock.DnsProbe;

import java.util.List;

/**
 * DNS 交叉诊断纯逻辑单测（无 Android / JUnit 依赖）。
 * 运行：见 tests/jvm/run_tests.sh
 */
public class DnsProbeTest {
    private static int passed = 0, failed = 0;

    private static void check(String name, boolean cond) {
        if (cond) {
            passed++;
            System.out.println("  PASS " + name);
        } else {
            failed++;
            System.out.println("  FAIL " + name);
        }
    }

    public static void main(String[] args) {
        System.out.println("[DnsProbe] 判定矩阵");
        check("本机正常 + 权威正常 → 无结论(不干预)",
                DnsProbe.judge(true, DnsProbe.REMOTE_OK) == DnsProbe.NONE);
        check("本机正常 + 权威失败 → 无结论",
                DnsProbe.judge(true, DnsProbe.REMOTE_ERROR) == DnsProbe.NONE);
        check("本机失败 + 权威正常 → 本机DNS/hosts异常",
                DnsProbe.judge(false, DnsProbe.REMOTE_OK) == DnsProbe.LOCAL_DNS);
        check("本机失败 + 权威NXDOMAIN → 域名无记录",
                DnsProbe.judge(false, DnsProbe.REMOTE_NODATA) == DnsProbe.REMOTE_MISS);
        check("本机失败 + 权威超时 → 无结论(不拿失败探针背书)",
                DnsProbe.judge(false, DnsProbe.REMOTE_ERROR) == DnsProbe.NONE);

        System.out.println("[DnsProbe] 文案映射");
        check("LOCAL_DNS 有可操作文案",
                DnsProbe.textOf(DnsProbe.LOCAL_DNS) != null
                        && DnsProbe.textOf(DnsProbe.LOCAL_DNS).contains("本机"));
        check("REMOTE_MISS 有可操作文案",
                DnsProbe.textOf(DnsProbe.REMOTE_MISS) != null
                        && DnsProbe.textOf(DnsProbe.REMOTE_MISS).contains("公网"));
        check("NONE 返回 null(沿用原文案)", DnsProbe.textOf(DnsProbe.NONE) == null);

        System.out.println("[DnsProbe] 查询报文构造");
        byte[] q = DnsProbe.buildQuery("www.example.com", 0x1234);
        check("报文总长 = 12 + QNAME(4+8+4+1=17) + 4 = 33",
                q.length == 12 + 17 + 4);
        check("事务 ID 高字节", (q[0] & 0xff) == 0x12);
        check("事务 ID 低字节", (q[1] & 0xff) == 0x34);
        check("RD 标志", (q[2] & 0xff) == 0x01 && (q[3] & 0xff) == 0x00);
        check("QDCOUNT=1", (q[4] & 0xff) == 0 && (q[5] & 0xff) == 1);
        check("QTYPE=A", q[q.length - 4] == 0 && (q[q.length - 3] & 0xff) == 1);
        check("QCLASS=IN", q[q.length - 2] == 0 && (q[q.length - 1] & 0xff) == 1);
        check("QNAME 标签编码 www=3", (q[12] & 0xff) == 3);
        check("QNAME 结束符(偏移28)", q[28] == 0);

        System.out.println("[DnsProbe] 响应解析");
        // 手工构造：头(ID=0x1234, qr=1, rcode=0, ANCOUNT=1)
        // question: 0xC00C 压缩指针 + A + IN
        // answer:   name=0xC00C, type=A, class=IN, ttl=60, rdlen=4, ip=93.184.216.34
        byte[] resp = new byte[12 + 6 + 16];
        resp[0] = 0x12; resp[1] = 0x34;
        resp[2] = (byte) 0x81; resp[3] = 0x00;   // QR=1, RD=1, rcode=0
        resp[5] = 1;                             // QDCOUNT=1
        resp[7] = 1;                             // ANCOUNT=1
        int o = 12;
        resp[o++] = (byte) 0xC0; resp[o++] = 0x0C;   // QNAME 压缩
        resp[o++] = 0; resp[o++] = 1;                // A
        resp[o++] = 0; resp[o++] = 1;                // IN
        resp[o++] = (byte) 0xC0; resp[o++] = 0x0C;   // ANNAME 压缩
        resp[o++] = 0; resp[o++] = 1;                // TYPE=A
        resp[o++] = 0; resp[o++] = 1;                // CLASS=IN
        resp[o++] = 0; resp[o++] = 0; resp[o++] = 0; resp[o++] = 60;  // TTL
        resp[o++] = 0; resp[o++] = 4;                // RDLENGTH
        resp[o++] = 93; resp[o++] = (byte) 184; resp[o++] = (byte) 216; resp[o++] = 34;
        List<String> ips = DnsProbe.parseARecords(resp, resp.length);
        check("解析出 1 条 A 记录", ips.size() == 1);
        check("A 记录 IP 正确", !ips.isEmpty() && "93.184.216.34".equals(ips.get(0)));

        check("压缩指针跳过 2 字节", DnsProbe.skipName(new byte[]{(byte) 0xC0, 0x0C, 0, 1}, 0) == 2);
        check("普通标签链跳过正确", DnsProbe.skipName(new byte[]{3, 'w', 'w', 'w', 0}, 0) == 5);

        // NXDOMAIN：rcode=3，即使 ANCOUNT 误写也不应产出 IP
        byte[] nx = resp.clone();
        nx[3] = 3;
        check("NXDOMAIN 不产出 IP", DnsProbe.parseARecords(nx, nx.length).isEmpty());
        // 截断报文不应抛异常
        check("短报文安全返回空", DnsProbe.parseARecords(new byte[]{1, 2, 3}, 3).isEmpty());

        System.out.println(String.format("\nRESULT: %d passed, %d failed", passed, failed));
        if (failed > 0) System.exit(1);
    }
}
