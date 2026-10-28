import sys, struct, zlib

def load_png(path):
    with open(path, "rb") as f:
        data = f.read()
    pos = 8
    w = h = None
    idat = b""
    while pos < len(data):
        ln = struct.unpack(">I", data[pos:pos+4])[0]
        typ = data[pos+4:pos+8]
        chunk = data[pos+8:pos+8+ln]
        if typ == b"IHDR":
            w, h, bd, ct = struct.unpack(">IIBB", chunk[:10])
            assert bd == 8, "only 8-bit supported"
            nch = {0:1, 2:3, 3:1, 4:2, 6:4}[ct]
        elif typ == b"IDAT":
            idat += chunk
        elif typ == b"IEND":
            break
        pos += 12 + ln
    raw = zlib.decompress(idat)
    stride = w * nch
    out = bytearray(w * h * nch)
    prev = bytearray(stride)
    p = 0
    for y in range(h):
        ft = raw[p]; p += 1
        line = bytearray(raw[p:p+stride]); p += stride
        if ft == 1:
            for i in range(nch, stride):
                line[i] = (line[i] + line[i-nch]) & 255
        elif ft == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 255
        elif ft == 3:
            for i in range(stride):
                a = line[i-nch] if i >= nch else 0
                line[i] = (line[i] + ((a + prev[i]) >> 1)) & 255
        elif ft == 4:
            for i in range(stride):
                a = line[i-nch] if i >= nch else 0
                b = prev[i]
                c = prev[i-nch] if i >= nch else 0
                pa, pb, pc = abs(b-c), abs(a-c), abs(a+b-2*c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 255
        out[y*stride:(y+1)*stride] = line
        prev = line
    return w, h, nch, out

def find_color(w, h, nch, buf, hexcol, tol=26):
    r0, g0, b0 = int(hexcol[0:2],16), int(hexcol[2:4],16), int(hexcol[4:6],16)
    pts = []
    for y in range(0, h, 2):
        row = y*w*nch
        for x in range(0, w, 2):
            i = row + x*nch
            r, g, b = buf[i], buf[i+1], buf[i+2]
            if abs(r-r0) <= tol and abs(g-g0) <= tol and abs(b-b0) <= tol:
                pts.append((x, y))
    if not pts:
        return None
    xs = [p[0] for p in pts]; ys = [p[1] for p in pts]
    return {
        "count": len(pts),
        "cx": sum(xs)//len(xs), "cy": sum(ys)//len(ys),
        "minx": min(xs), "maxx": max(xs), "miny": min(ys), "maxy": max(ys),
    }

if __name__ == "__main__":
    path = sys.argv[1]
    w, h, nch, buf = load_png(path)
    print("size", w, h, "channels", nch)
    for hexcol in sys.argv[2:]:
        res = find_color(w, h, nch, buf, hexcol)
        print(hexcol, res)
