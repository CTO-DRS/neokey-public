#!/usr/bin/env python3
# align_apk.py — week 31 (phase 6 W2): deterministic APK assembly step.
#
#   usage: ci/align_apk.py <base.apk> <classes.dex> <out.apk>
#
# Takes the aapt2-linked base APK, injects the pipeline R8 classes.dex, forces
# resources.arsc to STORED (uncompressed — the API-30+ install requirement for
# targetSdk >= 30) and 4-byte aligns every STORED entry's data offset by
# padding the local-header extra field (the zipalign contract), so the output
# is install-ready and deterministically reproducible:
#   - every entry gets fixed date_time (1980,1,1,0,0,0) and uniform attrs
#   - deflate = zlib raw level 9, fixed parameters
# Same inputs → byte-identical output (the SIGNEDAPK gate's determinism check
# rides on this). Signature happens AFTER alignment (apksig signs the aligned
# archive; v2 covers the whole zip).
import sys, zlib, zipfile, struct

def main():
    if len(sys.argv) != 4:
        print("usage: align_apk.py <base.apk> <classes.dex> <out.apk>", file=sys.stderr)
        return 2
    base, dex_path, out = sys.argv[1], sys.argv[2], sys.argv[3]
    dex = open(dex_path, "rb").read()

    entries = []  # (name, data, compress_type)
    with zipfile.ZipFile(base) as zin:
        for zi in zin.infolist():
            if zi.filename == "classes.dex":
                continue  # the pipeline dex replaces anything the linker carried
            data = zin.read(zi.filename)
            ct = zipfile.ZIP_STORED if zi.filename == "resources.arsc" else (
                zi.compress_type if zi.compress_type in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED)
                else zipfile.ZIP_DEFLATED)
            entries.append((zi.filename, data, ct))
    entries.insert(0, ("classes.dex", dex, zipfile.ZIP_DEFLATED))

    with zipfile.ZipFile(out, "w", allowZip64=False) as z:
        for name, data, ct in entries:
            zi = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            zi.compress_type = ct
            zi.external_attr = 0o644 << 16
            nb = name.encode("utf-8")
            if ct == zipfile.ZIP_STORED:
                # predict this entry's local-header offset and pad the extra
                # field so the DATA starts on a 4-byte boundary
                off = z.fp.tell()
                pad = (4 - ((off + 30 + len(nb)) % 4)) % 4
                zi.extra = b"\x00" * pad
            z.writestr(zi, data)
    # self-check: every STORED entry aligned, arsc stored
    with zipfile.ZipFile(out) as z:
        for zi in z.infolist():
            if zi.compress_type == zipfile.ZIP_STORED:
                off = z.getinfo(zi.filename).header_offset
                nb = zi.filename.encode("utf-8")
                extra = len(zi.extra or b"")
                data_off = off + 30 + len(nb) + extra
                assert data_off % 4 == 0, f"{zi.filename} data at {data_off} not aligned"
        a = z.getinfo("resources.arsc")
        assert a.compress_type == zipfile.ZIP_STORED, "arsc not stored"
    print(f"ALIGN_OK entries={len(entries)} dex={len(dex)}B arsc=stored+4B-aligned out={out}")
    return 0

if __name__ == "__main__":
    sys.exit(main())
