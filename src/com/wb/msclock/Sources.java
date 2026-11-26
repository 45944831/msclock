package com.wb.msclock;

/**
 * 时间源单一数据源（纯 JVM，可单测）。
 *
 * <p>此前 TimeSync 用 KEYS/NAMES/COLORS/KINDS/URLS/DOMAINS 六个平行数组手工按下标对齐，
 * 新增/调整一个源要同时改六处，极易错位；MainActivity 又复制了一份 ALL_KEYS。
 * 现在每个源只在 ALL 中定义一次，六个数组全部派生，错位在结构上不再可能。
 */
public final class Sources {
    private Sources() { }

    public static final class Source {
        public final String key;
        public final String name;
        public final String color;   // #RRGGBB，由 TimeSync.parseColor 解析
        public final String kind;    // none / ms / date / ntp / gnss / sysnet
        public final String url;     // null 表示不联网
        public final String domain;  // 展示用域名/说明

        Source(String key, String name, String color, String kind, String url, String domain) {
            this.key = key;
            this.name = name;
            this.color = color;
            this.kind = kind;
            this.url = url;
            this.domain = domain;
        }

        /** 可选源：失败不进失败明细、不计入总数（室内无 GNSS 信号属正常） */
        public boolean optional() {
            return "gnss".equals(kind) || "sysnet".equals(kind);
        }
    }

    /** 唯一定义处，下标即源编号（device 固定占 0 位） */
    public static final Source[] ALL = {
            new Source("device", "设备时间", "#8A93A6", "none", null, "设备系统时钟"),
            new Source("taobao", "淘宝", "#FF6A00", "ms",
                    "https://h5api.m.taobao.com/h5/mtop.common.getTimestamp/1.0/?jsv=2.5.1&appKey=12574478&t=%d&sign=1",
                    "h5api.m.taobao.com"),
            new Source("tmall", "天猫", "#FF0036", "ms",
                    "https://h5api.m.tmall.com/h5/mtop.common.getTimestamp/1.0/?jsv=2.5.1&appKey=12574478&t=%d&sign=1",
                    "h5api.m.tmall.com"),
            new Source("jd", "京东", "#E8443A", "date",
                    "https://api.m.jd.com/api?appid=publicUseApi", "api.m.jd.com"),
            new Source("meituan", "美团", "#FFC300", "date",
                    "https://www.meituan.com/", "www.meituan.com"),
            new Source("eleme", "饿了么", "#1E90FF", "date",
                    "https://www.ele.me/", "www.ele.me"),
            new Source("vip", "唯品会", "#F10180", "date",
                    "https://www.vip.com/", "www.vip.com"),
            new Source("pdd", "拼多多", "#E02E24", "date",
                    "https://mobile.yangkeduo.com/", "mobile.yangkeduo.com"),
            new Source("bili", "哔哩哔哩", "#00A1D6", "date",
                    "https://www.bilibili.com/", "www.bilibili.com"),
            new Source("kuaishou", "快手", "#FF8800", "date",
                    "https://www.kuaishou.com/", "www.kuaishou.com"),
            new Source("xhs", "小红书", "#FF2442", "date",
                    "https://www.xiaohongshu.com/", "www.xiaohongshu.com"),
            new Source("alipay", "支付宝", "#1677FF", "date",
                    "https://my.alipay.com/", "my.alipay.com"),
            new Source("rail", "12306", "#1A5CC8", "date",
                    "https://www.12306.cn/index/", "www.12306.cn"),
            new Source("unionpay", "云闪付", "#005BAC", "date",
                    "https://www.unionpayintl.com/", "www.unionpayintl.com"),
            new Source("ntp", "阿里云NTP", "#3DDC84", "ntp",
                    "ntp.aliyun.com", "ntp.aliyun.com"),
            new Source("gnss", "GNSS授时", "#1E8E3E", "gnss", null, "系统 GNSS 时钟"),
            new Source("sysnet", "系统网络时间", "#7C4DFF", "sysnet", null, "系统网络时间")
    };

    /** 派生数组：供 TimeSync 现有按下标访问的代码直接使用，错位不可能发生 */
    public static final String[] KEYS = new String[ALL.length];
    public static final String[] NAMES = new String[ALL.length];
    public static final String[] COLORS = new String[ALL.length];
    public static final String[] KINDS = new String[ALL.length];
    public static final String[] URLS = new String[ALL.length];
    public static final String[] DOMAINS = new String[ALL.length];

    static {
        for (int i = 0; i < ALL.length; i++) {
            Source s = ALL[i];
            KEYS[i] = s.key;
            NAMES[i] = s.name;
            COLORS[i] = s.color;
            KINDS[i] = s.kind;
            URLS[i] = s.url;
            DOMAINS[i] = s.domain;
        }
    }

    public static int indexOf(String key) {
        if (key == null) return -1;
        for (int i = 0; i < ALL.length; i++) if (ALL[i].key.equals(key)) return i;
        return -1;
    }

    /** 结构性自检：key 唯一、device 位于 0 位、六个派生数组等长 */
    public static boolean consistent() {
        if (ALL.length == 0 || !"device".equals(ALL[0].key)) return false;
        if (KEYS.length != ALL.length || NAMES.length != ALL.length
                || COLORS.length != ALL.length || KINDS.length != ALL.length
                || URLS.length != ALL.length || DOMAINS.length != ALL.length) return false;
        for (int i = 0; i < ALL.length; i++) {
            if (indexOf(ALL[i].key) != i) return false;
            if (!KEYS[i].equals(ALL[i].key) || !NAMES[i].equals(ALL[i].name)) return false;
        }
        return true;
    }
}
