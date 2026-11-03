package com.hark.neokey;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * NQF — NeoKey Quantized Format v1 (roadmap Phase 1, item A1: «تنسيق أوزان ثنائي كمّي int8»).
 *
 * A self-describing binary container for quantized neural weights. Pure Java,
 * zero android.*, zero external libraries — the whole format is read and written
 * by this one class, JVM-tested as shipped, and small enough to survive R8
 * untouched until week A2 wires the load path (it is then expected to inline).
 *
 * ── File layout (all integers LITTLE-ENDIAN) ─────────────────────────────────
 *   offset  size  field
 *        0     4  magic "NQF1"
 *        4     2  version (u16, must be 1)
 *        6     2  flags (u16, reserved — writer emits 0, reader accepts any)
 *        8     4  tensorCount (u32)
 *       12     4  dirBytes (u32, byte size of the directory region)
 *       16     4  metaLen (u32, byte size of the UTF-8 meta JSON, 0 allowed)
 *       20   var  meta JSON bytes (UTF-8)
 *              var  directory: tensorCount entries, each:
 *                     u16 nameLen, name UTF-8 (nameLen bytes)
 *                     u8  dtype (1 = Q8 int8 symmetric, 2 = F32)
 *                     u8  rank (1..6)
 *                     rank × u32 dims
 *                     f32 scale (Q8: value = q * scale; F32: written as 1.0)
 *                     u32 dataOffset (absolute, points into the blob region)
 *                     u32 dataBytes
 *                     u32 crc32 (of the raw tensor bytes)
 *              var  tensor blobs (raw bytes, row-major, dims[0]*dims[1]*…)
 *        n-4    4  trailer: crc32 of bytes [0, n-4) — every byte of the file is
 *                  covered twice for blobs (per-tensor + trailer) and once for
 *                  the header/meta/directory, so any single-bit corruption is
 *                  refused instead of silently mispredicting.
 *
 * ── Strictness contract (every rule has a test that proves the refusal) ─────
 *   • magic/version exact; tensorCount ≤ 65535; metaLen ≤ 16MB
 *   • names non-empty and unique; dtype ∈ {1,2}; rank 1..6
 *   • dims product (as long) must equal dataBytes for Q8 and dataBytes/4 for F32
 *     (a dims/size disagreement is the classic tampered-model vector)
 *   • product must fit a Java array length; scale must be finite and > 0 for Q8
 *   • blobs live strictly after the directory and inside [0, n-4)
 *   • per-tensor CRC32 AND whole-file trailer CRC32 must verify
 *   • the directory must be consumed exactly (no slack bytes)
 */
public final class NQF {

    public static final int DT_Q8 = 1;    // int8 symmetric quantized weights
    public static final int DT_F32 = 2;   // float32 (biases, layernorm, meta numbers)
    public static final int VERSION = 1;  // format version this class reads and writes
    private static final int HEADER = 20; // magic(4)+ver(2)+flags(2)+count(4)+dir(4)+meta(4)
    private static final int TRAILER = 4;
    private static final int MAX_TENSORS = 65535;
    private static final int MAX_META = 1 << 24; // 16MB is far beyond any vocab meta we will ship

    private NQF() { }

    /** Thrown for ANY structural/integrity violation — a corrupted model must
     *  never reach the engine as garbage (negative proofs in NNetTest/NNetGate). */
    public static final class BadFormat extends RuntimeException {
        public BadFormat(String m) { super(m); }
    }

    /** One tensor: quantized bytes + dequantization scale (Q8) or float data (F32). */
    public static final class Tensor {
        public final String name;
        public final int dtype;
        public final int[] dims;
        public final float scale;   // Q8 only; 1.0f for F32
        public final byte[] q8;     // Q8 payload (size = product of dims)
        public final float[] f32;   // F32 payload (null for Q8)

        Tensor(String name, int dtype, int[] dims, float scale, byte[] q8, float[] f32) {
            this.name = name; this.dtype = dtype; this.dims = dims;
            this.scale = scale; this.q8 = q8; this.f32 = f32;
        }
        /** Total element count (product of dims). */
        public int size() {
            long p = 1;
            for (int d : dims) p *= d;
            return (int) p;
        }
        /** value = q * scale for element i (Q8 convenience). */
        public float q8At(int i) { return q8[i] * scale; }
    }

    /** A parsed model: tensors by name + the meta JSON string. */
    public static final class File {
        public final String meta;                      // may be ""
        private final Map<String, Tensor> byName;
        File(String meta, Map<String, Tensor> byName) { this.meta = meta; this.byName = byName; }
        public Tensor tensor(String name) {
            Tensor t = byName.get(name);
            if (t == null) throw new BadFormat("tensor not found: " + name);
            return t;
        }
        public boolean has(String name) { return byName.containsKey(name); }
        public int tensorCount() { return byName.size(); }
        public List<String> names() { return new ArrayList<String>(byName.keySet()); }
    }

    // ════════════════════════════ WRITER ════════════════════════════

    /** Accumulates tensors and serializes one NQF v1 file. */
    public static final class Builder {
        private final List<Tensor> order = new ArrayList<Tensor>();
        private String meta = "";

        public Builder meta(String json) {
            if (json == null) json = "";
            byte[] b = json.getBytes(StandardCharsets.UTF_8);
            if (b.length > MAX_META) throw new IllegalArgumentException("meta too large: " + b.length);
            this.meta = json;
            return this;
        }
        public Builder add(String name, int[] dims, byte[] q8, float scale) {
            checkNew(name); checkDims(dims);
            long want = product(dims);
            if (q8 == null) throw new IllegalArgumentException(name + ": null Q8 payload");
            if (want != q8.length) throw new IllegalArgumentException(
                    name + ": dims product " + want + " != payload " + q8.length);
            if (!(scale > 0f) || !isFinite(scale))
                throw new IllegalArgumentException(name + ": Q8 scale must be finite > 0, got " + scale);
            order.add(new Tensor(name, DT_Q8, dims, scale, q8, null));
            return this;
        }
        public Builder addF32(String name, int[] dims, float[] data) {
            checkNew(name); checkDims(dims);
            long want = product(dims);
            if (data == null) throw new IllegalArgumentException(name + ": null F32 payload");
            if (want != data.length) throw new IllegalArgumentException(
                    name + ": dims product " + want + " != payload " + data.length);
            order.add(new Tensor(name, DT_F32, dims, 1.0f, null, data));
            return this;
        }
        private void checkNew(String name) {
            if (name == null || name.isEmpty()) throw new IllegalArgumentException("empty tensor name");
            if (name.length() > 255) throw new IllegalArgumentException("tensor name too long: " + name);
            for (Tensor t : order) if (t.name.equals(name))
                throw new IllegalArgumentException("duplicate tensor name: " + name);
        }
        private static void checkDims(int[] dims) {
            if (dims == null || dims.length < 1 || dims.length > 6)
                throw new IllegalArgumentException("rank must be 1..6");
            long p = 1;
            for (int d : dims) {
                if (d < 1) throw new IllegalArgumentException("dims must be >= 1");
                p *= d;
                if (p > Integer.MAX_VALUE) throw new IllegalArgumentException("tensor too large: " + p);
            }
        }
        /** Exact byte size build() will produce — the format-size documentation number. */
        public long expectedBytes() {
            long n = HEADER + meta.getBytes(StandardCharsets.UTF_8).length;
            for (Tensor t : order) n += entryBytes(t);
            for (Tensor t : order) n += t.dtype == DT_Q8 ? t.q8.length : 4L * t.f32.length;
            return n + TRAILER;
        }
        private static long entryBytes(Tensor t) {
            return 2 + t.name.getBytes(StandardCharsets.UTF_8).length + 2 + 4L * t.dims.length
                    + 4 + 4 + 4 + 4;
        }

        public byte[] build() {
            byte[] metaB = meta.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(expectedBytes() + 16, Integer.MAX_VALUE - 8));
            // fixed header (offsets patched in as we know them)
            long cursor = HEADER + metaB.length;
            for (Tensor t : order) cursor += entryBytes(t);
            long blobStart = cursor;

            w4(out, 0x3146514E);          // "NQF1" little-endian
            w2(out, VERSION);
            w2(out, 0);                   // flags
            w4(out, order.size());
            long dirBytes = 0;
            for (Tensor t : order) dirBytes += entryBytes(t);
            w4(out, (int) dirBytes);
            w4(out, metaB.length);
            out.write(metaB, 0, metaB.length);

            // directory (offsets are absolute, blobs follow the directory sequentially)
            long off = blobStart;
            for (Tensor t : order) {
                byte[] nb = t.name.getBytes(StandardCharsets.UTF_8);
                w2(out, nb.length); out.write(nb, 0, nb.length);
                out.write(t.dtype);
                out.write(t.dims.length);
                for (int d : t.dims) w4(out, d);
                wf(out, t.scale);
                long bytes = t.dtype == DT_Q8 ? t.q8.length : 4L * t.f32.length;
                w4(out, (int) off); w4(out, (int) bytes);
                CRC32 crc = new CRC32();
                if (t.dtype == DT_Q8) crc.update(t.q8, 0, t.q8.length);
                else {
                    byte[] fb = f32Bytes(t.f32);
                    crc.update(fb, 0, fb.length);
                }
                w4(out, (int) crc.getValue());
                off += bytes;
            }
            // blobs
            for (Tensor t : order) {
                if (t.dtype == DT_Q8) out.write(t.q8, 0, t.q8.length);
                else { byte[] fb = f32Bytes(t.f32); out.write(fb, 0, fb.length); }
            }
            // trailer: CRC32 of everything before it
            byte[] all = out.toByteArray();
            CRC32 file = new CRC32();
            file.update(all, 0, all.length);
            w4(out, (int) file.getValue());
            all = out.toByteArray();
            if (all.length != expectedBytes())
                throw new IllegalStateException("writer accounting broke: " + all.length + " != " + expectedBytes());
            return all;
        }
        private static byte[] f32Bytes(float[] v) {
            byte[] b = new byte[v.length * 4];
            for (int i = 0; i < v.length; i++) {
                int bits = Float.floatToIntBits(v[i]);
                b[i * 4]     = (byte) bits;
                b[i * 4 + 1] = (byte) (bits >>> 8);
                b[i * 4 + 2] = (byte) (bits >>> 16);
                b[i * 4 + 3] = (byte) (bits >>> 24);
            }
            return b;
        }
    }

    // ════════════════════════════ READER ════════════════════════════

    /** Strict reader: any violation throws BadFormat — never returns a half-built model. */
    public static File read(byte[] b) {
        if (b == null) throw new BadFormat("null bytes");
        need(b.length >= HEADER + TRAILER, "file too small: " + b.length);
        need(b[0] == 'N' && b[1] == 'Q' && b[2] == 'F' && b[3] == '1',
                "bad magic: " + Integer.toHexString(b[0] & 0xFF) + " " + Integer.toHexString(b[1] & 0xFF));
        int version = u16(b, 4);
        need(version == VERSION, "unsupported version " + version);
        // flags: reserved, accepted but ignored (forward compatibility)

        // trailer CRC first — the cheapest whole-file integrity check
        CRC32 file = new CRC32();
        file.update(b, 0, b.length - TRAILER);
        int wantCrc = (b[b.length - 4] & 0xFF) | (b[b.length - 3] & 0xFF) << 8
                | (b[b.length - 2] & 0xFF) << 16 | (b[b.length - 1] & 0xFF) << 24;
        need((int) file.getValue() == wantCrc, "file CRC mismatch (corrupted or truncated model)");

        int count = u32(b, 8);
        need(count <= MAX_TENSORS, "tensorCount absurd: " + count);
        int dirBytes = u32(b, 12);
        int metaLen = u32(b, 16);
        need(metaLen <= MAX_META, "metaLen absurd: " + metaLen);
        long pos = HEADER;
        need(pos + metaLen <= (long) b.length - TRAILER, "meta overruns file");
        String meta = new String(b, (int) pos, metaLen, StandardCharsets.UTF_8);
        pos += metaLen;
        int dirStart = (int) pos;
        need((long) dirStart + dirBytes <= (long) b.length - TRAILER, "directory overruns file");

        // structural walk: validate bounds/structure of every entry
        Map<String, Tensor> map = new LinkedHashMap<String, Tensor>();
        long p = dirStart;
        int dirEnd = dirStart + dirBytes;
        for (int i = 0; i < count; i++) {
            need(p + 2 <= dirEnd, "directory truncated (entry " + i + ")");
            int nameLen = u16(b, (int) p); p += 2;
            need(nameLen >= 1, "empty tensor name (entry " + i + ")");
            need(p + nameLen + 2 <= dirEnd, "name overruns directory (entry " + i + ")");
            p += nameLen;
            int dtype = b[(int) p] & 0xFF; p += 1;
            int rank = b[(int) p] & 0xFF; p += 1;
            need(dtype == DT_Q8 || dtype == DT_F32, "bad dtype " + dtype + " (entry " + i + ")");
            need(rank >= 1 && rank <= 6, "bad rank " + rank + " (entry " + i + ")");
            need(p + 4L * rank <= dirEnd, "dims overruns directory (entry " + i + ")");
            p += 4L * rank;
            p += 4; // scale — validated in the materialize pass
            need(p + 12 <= dirEnd, "offsets overruns directory (entry " + i + ")");
            p += 12;
        }
        need(p == dirEnd, "directory slack: " + (dirEnd - p) + " unconsumed bytes");

        // materialize pass
        p = dirStart;
        for (int i = 0; i < count; i++) {
            int nameLen = u16(b, (int) p); p += 2;
            String name = new String(b, (int) p, nameLen, StandardCharsets.UTF_8);
            p += nameLen;
            need(!map.containsKey(name), "duplicate tensor name: " + name);
            int dtype = b[(int) p++] & 0xFF;
            int rank = b[(int) p++] & 0xFF;
            int[] dims = new int[rank];
            long prod = 1;
            for (int d = 0; d < rank; d++) {
                dims[d] = u32(b, (int) p); p += 4;
                need(dims[d] >= 1, "dim < 1 in " + name);
                prod *= dims[d];
                need(prod <= Integer.MAX_VALUE, "tensor too large: " + name);
            }
            float scale = Float.intBitsToFloat(u32(b, (int) p)); p += 4;
            long dataOff = u32(b, (int) p) & 0xFFFFFFFFL; p += 4;
            long dataBytes = u32(b, (int) p) & 0xFFFFFFFFL; p += 4;
            int crc = u32(b, (int) p); p += 4;
            if (dtype == DT_Q8) {
                need(isFinite(scale) && scale > 0f, "Q8 scale must be finite > 0 in " + name + " (got " + scale + ")");
                need(prod == dataBytes, name + ": dims product " + prod + " != dataBytes " + dataBytes);
                need(dataOff >= dirStart + dirBytes, name + ": blob overlaps header/directory");
                need(dataOff + dataBytes <= (long) b.length - TRAILER, name + ": blob overruns file");
                byte[] q8 = Arrays.copyOfRange(b, (int) dataOff, (int) (dataOff + dataBytes));
                CRC32 c = new CRC32();
                c.update(q8, 0, q8.length);
                need((int) c.getValue() == crc, name + ": tensor CRC mismatch");
                map.put(name, new Tensor(name, DT_Q8, dims, scale, q8, null));
            } else {
                need(dataBytes % 4 == 0 && prod == dataBytes / 4,
                        name + ": dims product " + prod + " != dataBytes/4 " + (dataBytes / 4));
                need(dataOff >= dirStart + dirBytes, name + ": blob overlaps header/directory");
                need(dataOff + dataBytes <= (long) b.length - TRAILER, name + ": blob overruns file");
                float[] f = new float[(int) prod];
                for (int j = 0; j < f.length; j++) {
                    int bits = (b[(int) dataOff + j * 4] & 0xFF)
                            | (b[(int) dataOff + j * 4 + 1] & 0xFF) << 8
                            | (b[(int) dataOff + j * 4 + 2] & 0xFF) << 16
                            | (b[(int) dataOff + j * 4 + 3] & 0xFF) << 24;
                    f[j] = Float.intBitsToFloat(bits);
                }
                CRC32 c = new CRC32();
                byte[] fb = Arrays.copyOfRange(b, (int) dataOff, (int) (dataOff + dataBytes));
                c.update(fb, 0, fb.length);
                need((int) c.getValue() == crc, name + ": tensor CRC mismatch");
                map.put(name, new Tensor(name, DT_F32, dims, 1.0f, null, f));
            }
        }
        return new File(meta, map);
    }

    // ════════════════════════════ helpers ════════════════════════════

    private static long product(int[] dims) {
        long p = 1;
        for (int d : dims) p *= d;
        return p;
    }
    private static boolean isFinite(float f) { return !Float.isNaN(f) && !Float.isInfinite(f); }
    private static void need(boolean ok, String msg) { if (!ok) throw new BadFormat(msg); }

    private static int u16(byte[] b, int off) {
        return (b[off] & 0xFF) | (b[off + 1] & 0xFF) << 8;
    }
    private static int u32(byte[] b, int off) {
        return (b[off] & 0xFF) | (b[off + 1] & 0xFF) << 8 | (b[off + 2] & 0xFF) << 16 | (b[off + 3] & 0xFF) << 24;
    }
    private static void w2(ByteArrayOutputStream o, int v) {
        o.write(v); o.write(v >>> 8);
    }
    private static void w4(ByteArrayOutputStream o, int v) {
        o.write(v); o.write(v >>> 8); o.write(v >>> 16); o.write(v >>> 24);
    }
    private static void wf(ByteArrayOutputStream o, float f) { w4(o, Float.floatToIntBits(f)); }
}
