package com.hark.neokey;
/** Loads the writer templates asset then runs the full compose grid. */
public class W54WriterInit {
    public static void main(String[] a) throws Exception {
        byte[] raw = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("assets/writer_templates.txt"));
        Writer.init(raw);
        W54Probe.main(a);
    }
}
