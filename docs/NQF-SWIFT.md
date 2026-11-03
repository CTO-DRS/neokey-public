# NQF-SWIFT — مواصفة قارئ NQF v1 لـ iOS (المرحلة 2، البند C)

> هذه المواصفة مكتوبة **ومربوطة بالمتجهات الذهبية** (`docs/NQF-GOLDEN.json` /
> `docs/NQF-PORT.md`). القبول في iOS ليس «قرأ الملف» — بل **أعاد الأرقام نفسها
> بت-لطرف** في اختبارات XCTest. كل ما يلزم منفّذ Swift هنا: الأنواع، خوارزمية
> التحليل، قواعد الرفض، وقائمة القبول بأرقام حقيقية من نموذج الشحن.

## 0. القواعد الخمس غير القابلة للتفاوض

1. **كل الأعداد Little-Endian** — على ARM64 استخدم تحميلاً يدوياً من `Data`
   (لا `load(as:)` المباشر على مواقع غير محاذاة بدون `loadUnaligned`).
2. **لا Float64 في أي مكان من مسار فك الكمّ.** الضرب `Int8 × Float32` في
   `Float` حصرياً؛ أي `Double` وسيط يغيّر بتات بعض القيم ويكسر `deqSha256`.
3. **المرجع هو بتات IEEE-754** (`Float.bitPattern`) لا العرض العشري.
4. **الرفض استثناء دائم** — `throws NQFError` بقواعد §4، لا إعادة نموذج ناقص أبداً.
5. **SHA-256 للتجميع، CRC32 للتعرّف على الانقلاب** — CRC32 بجدول 256 مدخلاً،
   polynomial `0xEDB88320` (انعكاسي)، قيمة أولية `0xFFFFFFFF` ومعكوسة بالنهاية
   (مطابقة لِـ `java.util.zip.CRC32`).

## 1. الأنواع

```swift
enum NQFDtype: UInt8 { case q8 = 1, f32 = 2 }

struct NQFTensor {
    let name: String        // UTF-8، غير فارغ، فريد
    let dtype: NQFDtype
    let dims: [UInt32]      // 1...6 أبعاد، كل بُعد > 0
    let scaleBits: UInt32   // بتات IEEE-754 الخام — المرجع للتحقق
    var scale: Float        // Float(bitPattern: scaleBits)
    let dataOffset: UInt32  // مطلق في الملف (من 0)
    let dataBytes: UInt32
    let crc32Stored: UInt32
    let raw: Data           // البايتات الخامة
}

enum NQFError: Error, Equatable {          // وسوم الرفض — مطابقة لعقد NQF-PORT
    case short, null, magic, version, tensorCount, metaSize, region, dirOverrun,
         nameEmpty, nameDup, dtype, rank, dims, product, size, scale,
         blobRange, blobOverlap, tensorCrc, dirSlack, trailerCrc
}

struct NQFFile {
    let version: UInt16, flags: UInt16
    let tensorCount: Int, metaLen: Int, dirBytes: Int
    let meta: String                    // معتم — يُمرَّر كما-هو
    let tensors: [NQFTensor]
    func tensor(_ name: String) throws -> NQFTensor
}
```

## 2. التحليل (الخوارزمية)

```swift
extension NQFFile {
    static func read(_ b: Data) throws -> NQFFile {
        let n = b.count
        guard n >= 24 else { throw NQFError.short }
        guard b.prefix(4) == Data("NQF1".utf8) else { throw NQFError.magic }
        let version = b.u16(4);  guard version == 1 else { throw NQFError.version }
        let flags = b.u16(6)                                  // محجوز — يقبل أي قيمة
        let tensorCount = Int(b.u32(8)); guard tensorCount <= 65535 else { throw NQFError.tensorCount }
        let dirBytes = Int(b.u32(12))
        let metaLen = Int(b.u32(16)); guard metaLen <= 1 << 24 else { throw NQFError.metaSize }

        let header = 20
        let metaStart = header, dirStart = header + metaLen
        let blobStart = dirStart + dirBytes, blobEnd = n - 4
        guard dirStart + dirBytes <= blobEnd else { throw NQFError.region }
        let meta = String(data: b.subdata(in: metaStart..<dirStart), encoding: .utf8) ?? ""

        var tensors: [NQFTensor] = [];  var seen = Set<String>()
        var p = dirStart, dirRead = 0
        for _ in 0..<tensorCount {
            guard p + 2 <= blobEnd else { throw NQFError.dirOverrun }
            let nameLen = Int(b.u16(p)); p += 2; dirRead += 2
            guard nameLen > 0 else { throw NQFError.nameEmpty }
            guard p + nameLen + 2 <= blobEnd else { throw NQFError.dirOverrun }
            let name = String(data: b.subdata(in: p..<p+nameLen), encoding: .utf8)!
            p += nameLen; dirRead += nameLen
            guard seen.insert(name).inserted else { throw NQFError.nameDup }
            let dtype = b[p]; p += 1
            let rank = Int(b[p]); p += 1; dirRead += 2
            guard dtype == 1 || dtype == 2 else { throw NQFError.dtype }
            guard (1...6).contains(rank) else { throw NQFError.rank }
            guard p + rank*4 + 4 <= blobEnd else { throw NQFError.dirOverrun }
            var dims: [UInt32] = []; var product: UInt64 = 1
            for _ in 0..<rank { let d = b.u32(p); p += 4; dirRead += 4
                guard d > 0 else { throw NQFError.dims }
                product *= UInt64(d); guard product <= UInt64(Int32.max) else { throw NQFError.product }
                dims.append(d) }
            let scaleBits = b.u32(p); p += 4; dirRead += 4
            if dtype == 1 { let s = Float(bitPattern: scaleBits)
                guard s.isFinite && s > 0 else { throw NQFError.scale } }
            let off = b.u32(p); p += 4; dirRead += 4
            let dlen = b.u32(p); p += 4; dirRead += 4
            let want: UInt64 = dtype == 1 ? product : product * 4
            guard UInt64(dlen) == want else { throw NQFError.size }
            guard p + 4 <= blobEnd else { throw NQFError.dirOverrun }
            let crcStored = b.u32(p); p += 4; dirRead += 4
            let abs_ = Int(off)
            guard abs_ >= blobStart && abs_ + Int(dlen) <= blobEnd else { throw NQFError.blobRange }
            guard !tensors.contains(where: { $0.dataOffset < abs_ + Int(dlen)
                                            && abs_ < Int($0.dataOffset) + Int($0.dataBytes) })
            else { throw NQFError.blobOverlap }
            let raw = b.subdata(in: abs_..<abs_+Int(dlen))
            guard crc32(raw) == crcStored else { throw NQFError.tensorCrc }
            tensors.append(NQFTensor(name: name, dtype: NQFDtype(rawValue: dtype)!,
                                     dims: dims, scaleBits: scaleBits,
                                     scale: Float(bitPattern: scaleBits),
                                     dataOffset: off, dataBytes: dlen,
                                     crc32Stored: crcStored, raw: raw))
        }
        guard dirRead == dirBytes else { throw NQFError.dirSlack }
        guard b.u32(n-4) == crc32(b.prefix(n-4)) else { throw NQFError.trailerCrc }
        return NQFFile(version: version, flags: flags, tensorCount: tensorCount,
                       metaLen: metaLen, dirBytes: dirBytes, meta: meta, tensors: tensors)
    }
}
```

أدوات Little-Endian (بلا محاذاة):

```swift
extension Data {
    func u16(_ o: Int) -> UInt16 { UInt16(self[o]) | UInt16(self[o+1]) << 8 }
    func u32(_ o: Int) -> UInt32 { UInt32(self[o]) | UInt32(self[o+1]) << 8
                                 | UInt32(self[o+2]) << 16 | UInt32(self[o+3]) << 24 }
}
```

## 3. إزالة الكمّ والتجميع القياسي

```swift
extension NQFTensor {
    /// القيم منزوعة الكمّ — Float32 حصرياً (لا Double).
    var deqBitPatterns: [UInt32] {
        if dtype == .f32 {
            return (0..<Int(dataBytes)/4).map { raw.u32($0 * 4) }
        }
        let s = scale
        return raw.map { Float(Int8(bitPattern: $0)) * s }   // Float32 multiply
                  .map { $0.bitPattern }
    }
    /// deqSha256 — SHA-256 على 4 بايتات big-endian لبتات كل قيمة بالترتيب.
    var deqSha256: String { SHA256.hash(data: deqBitPatterns.flatMap(bigEndianBytes)).hex }
}

func crc32(_ d: Data) -> UInt32 {          // مطابق لِـ java.util.zip.CRC32
    var table = [UInt32](repeating: 0, count: 256)
    for i in 0..<256 { var c = UInt32(i)
        for _ in 0..<8 { c = (c & 1) == 1 ? 0xEDB88320 ^ (c >> 1) : c >> 1 }
        table[i] = c }
    var c: UInt32 = 0xFFFFFFFF
    for byte in d { c = table[Int((c ^ UInt32(byte)) & 0xFF)] ^ (c >> 8) }
    return c ^ 0xFFFFFFFF
}
```

## 4. قائمة القبول (XCTest) — أرقام حقيقية من نموذج الشحن `nnet_ar.nqf`

| # | الاختبار | القيمة المتوقعة (بت-لطرف) |
|---|---|---|
| 1 | `sha256(modelBytes)` | `7100f5d45ad0cdc0…` (كامل في NQF-GOLDEN.json) |
| 2 | `bytes / tensorCount / metaLen / dirBytes` | `3,004,240 / 8 / 307 / 246` |
| 3 | `tensor("emb")` | dtype `.q8`، dims `[9300, 64]`، scaleBits `1000430812`، crc32 `1099306456`، sha256 `75d5d685077574da…` |
| 4 | `tensor("out.w")` | dims `[9300, 240]`، scaleBits `1013826804`، deqSha256 `8d9635a11a2bd5a6…` |
| 5 | `tensor("fc0.b")` | dtype `.f32`، dims `[240]`، scaleBits `1065353216` (= 1.0f) |
| 6 | `paramsQ8` | `2,963,583` |
| 7 | `deqSha256` لكل الموترات الثمانية | مطابقة لعمود `deqSha256` في NQF-GOLDEN.json |
| 8 | رفض سحر مقلوب / إصدار 2 / مقياس صفر / انقلاب بايت في blob / انقلاب في المقتبس | `.magic` / `.version` / `.scale` / `.tensorCrc` / `.trailerCrc` |
| 9 | 96 انقلاباً عشوائياً حتمياً (نفس بذرة البوابة 1616) | رفض 96/96 |
| 10 | قواميس: `sha256(ar.txt)` | `fcc6cf243371183d…`، 150,123 سطراً فريداً |

> القيم الكاملة 64-هكس في `docs/NQF-GOLDEN.json` — تُضمَّن إلى bundle الاختبار
> في iOS ويُقارن بها مباشرة، فتتحول المتجهات الذهبية إلى عقد تنفيذي لا وصف.

## 5. ما لا يتطلبه القارئ

- تحليل ميتا JSON (معتم — تُمرَّر `meta` كما-هو).
- محاذاة الذاكرة (كل التحميلات بايت-ببايت).
- أي اعتماد خارجي — SHA256 من CryptoKit مقبول، CRC32 بالكود أعلاه.
- الأداء: هذا مسار تحميل بارد لمرة واحدة؛ بوابات الأداء خاصة بمسار الإستدلال
  ولا تُقاس في اختبارات القارئ.
