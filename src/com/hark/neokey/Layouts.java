package com.hark.neokey;

import java.util.ArrayList;
import java.util.List;

/** Key layouts: Arabic, English, two symbol pages, number row, emoji pages. */
final class Layouts {
    static final int AR = 0, EN = 1, SYM = 2, SYM2 = 3, NUM = 4;

    private static final String DIGITS = "1234567890";
    private static final String AR_DIGITS = "١٢٣٤٥٦٧٨٩٠";

    private Layouts() {}

    static final float EXTRA_W = 0.78f;
    /** Special layouts: 0 standard, 1 programming, 2 gaming, 3 messaging. */
    static final int L_STD = 0, L_CODE = 1, L_GAME = 2, L_CHAT = 3;

    static List<List<Key>> build(int mode, boolean arabic, boolean numberRow, boolean indic) {
        return build(mode, arabic, numberRow, indic, L_STD);
    }

    /** The extra top row for the programming / gaming / messaging layouts. */
    static List<Key> extraRow(int kind) {
        List<Key> r = new ArrayList<>();
        switch (kind) {
            case L_CODE: {
                r.add(new Key(Key.CHAR, "⇥", "\t", 1.2f, null));
                r.addAll(chars("{ } [ ] ( ) ; = < > /", new String[][]{
                        {"{", "{}", "${"}, null, {"[", "[]"}, null, {"(", "()"}, null, {";", ":", "::"},
                        {"=", "==", "===", "!=", "=>", "+=", "-="}, {"<", "<=", "</", "<<"}, {">", ">=", "->", ">>"},
                        {"/", "//", "\\", "/*", "*/"}}));
                break;
            }
            case L_GAME: {
                r.addAll(chars("gg wp glhf brb afk ez يلا قيم", new String[][]{
                        {"gg", "GG", "gg wp"}, {"wp", "WP"}, null, null, null, null, {"يلا", "يلا نلعب"}, {"قيم", "قيم ثاني"}}));
                for (Key k : r) k.units = 1.25f;
                break;
            }
            case L_CHAT: {
                for (String e : "😂 ❤️ 👍 🙏 😍 🔥 😭 🌹 ✅ 🤲".split(" ")) {
                    Key k = new Key(Key.CHAR, e, e, 1f, null);
                    k.id = 1; // emoji cell look
                    r.add(k);
                }
                break;
            }
            default: return null;
        }
        if (!r.isEmpty()) r.get(0).rowW = EXTRA_W;
        return r;
    }

    /** W117: the strip row the word suggestions live in (replaces the fixed emoji row). */
    static List<Key> stripRow() {
        List<Key> r = new ArrayList<>();
        Key k = new Key(Key.LABEL, "", null, 10f, null);
        k.id = Key.STRIP;
        k.rowW = EXTRA_W;
        r.add(k);
        return r;
    }

    static List<List<Key>> build(int mode, boolean arabic, boolean numberRow, boolean indic, int kind) {
        return build(mode, arabic, numberRow, indic, kind, false);
    }

    static List<List<Key>> build(int mode, boolean arabic, boolean numberRow, boolean indic, int kind, boolean strip) {
        List<List<Key>> rows = new ArrayList<>();
        boolean letters = mode == AR || mode == EN;
        if (letters && strip && (kind == L_STD || kind == L_CHAT)) rows.add(stripRow());
        else if (letters && kind != L_STD) {
            List<Key> ex = extraRow(kind);
            if (ex != null) rows.add(ex);
        }
        if (numberRow && letters) rows.add(numberRow(mode == AR ? 1.1f : 1.0f, indic));
        if (mode == NUM) return numpad(indic);
        switch (mode) {
            case AR: {
                List<Key> r1 = chars("ض ص ث ق ف غ ع ه خ ح ج", new String[][]{
                        null, null, null, null, null, null, null, {"ه", "ة"}, null, null, null});
                addHints(r1, indic);
                rows.add(r1);
                rows.add(chars("ش س ي ب ل ا ت ن م ك ط", new String[][]{
                        null, null, {"ي", "ئ", "ى"}, null, {"ل", "لا", "لأ", "لإ", "لآ"},
                        {"ا", "أ", "إ", "آ", "ء"}, null, null, null, null, null}));
                List<Key> r3 = chars("ذ ء ؤ ر ى ة و ز ظ د", new String[][]{
                        null, {"ء", "ئ", "ؤ"}, null, null, {"ى", "ي"}, null, {"و", "ؤ"}, null, null, null});
                r3.add(Key.fn(Key.DEL, "", 1.2f));
                rows.add(r3);
                rows.add(bottom(true, 3.8f, true));
                break;
            }
            case EN: {
                List<Key> r1 = chars("q w e r t y u i o p", new String[][]{
                        null, null, {"e", "é", "è", "ê", "ë"}, null, null, null,
                        {"u", "ú", "ü", "û"}, {"i", "í", "ï", "î"}, {"o", "ó", "ö", "ô"}, null});
                addHints(r1, false);
                rows.add(r1);
                rows.add(chars("a s d f g h j k l", new String[][]{
                        {"a", "á", "à", "â", "ä"}, {"s", "ß"}, null, null, null, null, null, null, null}));
                List<Key> r3 = new ArrayList<>();
                r3.add(Key.fn(Key.SHIFT, "", 1.5f));
                r3.addAll(chars("z x c v b n m", null));
                r3.add(Key.fn(Key.DEL, "", 1.5f));
                rows.add(r3);
                rows.add(bottom(false, 3.8f, false));
                break;
            }
            case SYM: {
                rows.add(chars("1 2 3 4 5 6 7 8 9 0", new String[][]{
                        {"1", "١", "¹", "½"}, {"2", "٢", "²"}, {"3", "٣", "³"}, {"4", "٤"}, {"5", "٥"},
                        {"6", "٦"}, {"7", "٧"}, {"8", "٨"}, {"9", "٩"}, {"0", "٠", "∅"}}));
                rows.add(chars("@ # $ _ & - + ( ) /", new String[][]{
                        null, null, {"$", "﷼", "€", "£", "¥"}, null, null, {"-", "–", "—"}, {"+", "±"},
                        {"(", "[", "{", "<"}, {")", "]", "}", ">"}, {"/", "\\"}}));
                List<Key> r3 = new ArrayList<>();
                r3.add(Key.fn(Key.TO_SYM2, "=\\<", 1.5f));
                r3.addAll(chars("* \" ' : ; ! ?", new String[][]{
                        {"*", "★", "†"}, {"\"", "«", "»", "“", "”"}, {"'", "‘", "’"}, null, {";", "؛"},
                        {"!", "¡"}, {"?", "؟", "¿"}}));
                r3.add(Key.fn(Key.DEL, "", 1.5f));
                rows.add(r3);
                rows.add(symBottom(arabic));
                break;
            }
            default: {
                rows.add(chars("~ ` | • √ π ÷ × ¶ ∆", null));
                rows.add(chars("£ € ¥ ^ ° = { } \\ %", new String[][]{
                        null, null, null, null, null, {"=", "≠", "≈"}, null, null, null, {"%", "‰", "٪"}}));
                List<Key> r3 = new ArrayList<>();
                r3.add(Key.fn(Key.TO_SYM, "?123", 1.5f));
                r3.addAll(chars("© ® ™ ✓ [ ] < >", null));
                r3.add(Key.fn(Key.DEL, "", 1.5f));
                rows.add(r3);
                rows.add(symBottom(arabic));
                break;
            }
        }
        return rows;
    }

    private static Key digit(int i, boolean indic) {
        String d = String.valueOf(i), a = String.valueOf((char) ('٠' + i));
        return indic ? new Key(Key.CHAR, a, a, 1f, new String[]{a, d}) : new Key(Key.CHAR, d, d, 1f, new String[]{d, a});
    }

    /** Big phone-style pad for number / phone fields. */
    private static List<List<Key>> numpad(boolean indic) {
        List<List<Key>> rows = new ArrayList<>();
        for (int r = 0; r < 3; r++) {
            List<Key> row = new ArrayList<>();
            for (int c = 1; c <= 3; c++) row.add(digit(r * 3 + c, indic));
            if (r == 0) row.add(Key.ch("-", 1f, "- + ( ) /"));
            else if (r == 1) row.add(new Key(Key.SPACE, "", " ", 1f, null));
            else row.add(Key.fn(Key.DEL, "", 1f));
            rows.add(row);
        }
        List<Key> last = new ArrayList<>();
        last.add(Key.fn(Key.TO_SYM, "+*#", 1f));
        last.add(Key.ch(",", 1f, ", ٫ ،"));
        last.add(digit(0, indic));
        last.add(Key.ch(".", 1f, ". ٫"));
        last.add(Key.fn(Key.ENTER, "", 1f));
        // keep 4 columns: comma and dot share the bottom row at smaller width
        last.get(1).units = 0.5f;
        last.get(3).units = 0.5f;
        rows.add(last);
        return rows;
    }

    private static List<Key> numberRow(float units, boolean indic) {
        List<Key> r = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String d = String.valueOf(DIGITS.charAt(i));
            String a = String.valueOf(AR_DIGITS.charAt(i));
            r.add(indic ? new Key(Key.CHAR, a, a, units, new String[]{a, d})
                        : new Key(Key.CHAR, d, d, units, new String[]{d, a}));
        }
        r.get(0).rowW = 0.84f;
        return r;
    }

    /** Small digit in the corner + long-press gives the digit (like modern keyboards). */
    private static void addHints(List<Key> row, boolean indic) {
        for (int i = 0; i < row.size() && i < 10; i++) {
            Key k = row.get(i);
            String d = String.valueOf((indic ? AR_DIGITS : DIGITS).charAt(i));
            k.hint = d;
            if (k.alts == null) k.alts = new String[]{d};
            else {
                String[] a = new String[k.alts.length + 1];
                a[0] = d;
                System.arraycopy(k.alts, 0, a, 1, k.alts.length);
                k.alts = a;
            }
        }
    }

    private static List<Key> bottom(boolean arabic, float space, boolean tashkeel) {
        List<Key> r = new ArrayList<>();
        r.add(Key.fn(Key.TO_SYM, "?123", 1.4f));
        r.add(arabic ? Key.ch("،", 1f, "، , ؛ :") : Key.ch(",", 1f, ", ; :"));
        r.add(Key.fn(Key.TO_EMOJI, "", 1f));
        r.add(new Key(Key.SPACE, arabic ? "العربية" : "English", " ", tashkeel ? space : space + 1f, null));
        r.add(arabic ? Key.ch(".", 1f, ". ؟ ! \" ' ( ) -") : Key.ch(".", 1f, ". ? ! ' \" : -"));
        if (tashkeel) r.add(Key.ch("\u064E", 1f, "\u064E \u064F \u0650 \u0652 \u0651 \u064B \u064C \u064D \u0640"));
        r.add(Key.fn(Key.ENTER, "", 1.8f));
        return r;
    }

    private static List<Key> symBottom(boolean arabic) {
        List<Key> r = new ArrayList<>();
        r.add(Key.fn(Key.TO_ALPHA, arabic ? "أبج" : "ABC", 1.4f));
        r.add(arabic ? Key.ch("،", 1f, "، ,") : Key.ch(",", 1f, ", ،"));
        r.add(Key.fn(Key.TO_EMOJI, "", 1f));
        r.add(new Key(Key.SPACE, "", " ", 3.8f, null));
        r.add(Key.ch(".", 1f, ". … ·"));
        r.add(Key.fn(Key.ENTER, "", 1.8f));
        return r;
    }

    static List<Key> chars(String s, String[][] alts) {
        String[] parts = s.split(" ");
        List<Key> r = new ArrayList<>();
        for (int i = 0; i < parts.length; i++) {
            String[] a = (alts != null && i < alts.length) ? alts[i] : null;
            r.add(new Key(Key.CHAR, parts[i], parts[i], 1f, a));
        }
        return r;
    }

    // ---------------------------------------------------------------- emoji

    static final String[] EMOJI_TABS = {"🕘", "😀", "👍", "❤️", "🐱", "🍔", "⚽", "🕌", "﷽", "★"};
    static final String[] EMOJI_TAB_ICONS = {"schedule", "mood", "hand", "favorite", "pets", "food", "sports", "mosque", null, null};
    static final String[][] EMOJI = {
            ("😀 😃 😄 😁 😆 😅 😂 🤣 😊 😇 🙂 😉 😌 😍 🥰 😘 😋 😜 🤪 😎 🥳 😏 😒 😔 "
                    + "😢 😭 😤 😡 🤯 😳 🥺 😱 🤔 🤗 🤭 🤫 😴 🙄").split(" "),
            ("👍 👎 👌 ✌️ 🤞 🤟 🤙 👋 👏 🙌 🙏 🤝 💪 👊 ✊ 🤲 👐 ☝️ 👆 👇 👉 👈 ✋ 🤚 "
                    + "🖐️ 👀 🧠 👶 🧒 👦 👧 🧔 👨 👩 🧕 👳").split(" "),
            ("❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 💯 ✨ ⭐ 🌟 🔥 💫 "
                    + "🎉 🎊 🎁 🌹 🌷 🌸 💐 🌺 🌙 ☀️ ⚡ 🌈").split(" "),
            ("🐱 🐶 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🐔 🐧 🐦 🦅 🦉 🐴 🐫 🐪 🐎 "
                    + "🐢 🐍 🐬 🐳 🐟 🦋 🐝 🌴 🌵 🌿 🍀 🌊").split(" "),
            ("🍔 🍕 🌭 🥪 🌮 🌯 🥙 🍟 🍗 🍖 🥩 🍳 🥞 🧇 🍞 🥐 🧀 🥗 🍚 🍛 🍝 🍣 🍤 🍩 "
                    + "🍪 🎂 🍰 🍫 🍦 🍉 🍇 🍓 🍌 🍎 ☕ 🧃").split(" "),
            ("⚽ 🏀 🏈 ⚾ 🎾 🏐 🏓 🏸 🥊 🏆 🥇 🎯 🎮 🕹️ 🎲 🎧 🎤 🎬 📷 📱 💻 ⌚ 💡 📚 "
                    + "✏️ 📌 📎 🔑 🔒 💰 💳 🛒 🎈 🧸 🚀 ✈️").split(" "),
            ("🕌 🕋 📿 🤲 🌙 ⭐ 🏠 🏢 🏫 🏥 🏨 🏝️ 🏜️ ⛰️ 🌍 🚗 🚕 🚌 🚑 🚓 🚲 🛵 🚆 🚢 "
                    + "⛽ 🚦 🇸🇦 🇦🇪 🇰🇼 🇶🇦 🇧🇭 🇴🇲 🇪🇬 🇯🇴 🇲🇦 🇵🇸").split(" "),
            ("﷽ ﷺ ﷻ ﷲ ﷴ ﷸ ﷹ ﷷ ﷶ ﷵ ﷳ ﷰ ﷱ ۞ ۩ ☪ ☪️ 🕋 🕌 📿 🤲 🌙 ☾ ☽ ﴾ ﴿ ۝ ؏ ؎ ٭ ※ ⁂ ﷼ ۵ ✵ ❁ ❀ ✿ ❃ ❋ ✾ ✽ ❊ ❉ ✺ ✹ ✸ ✷ ✶ ✴ ✳ ❂ ✦ ✧ ❖ ◈ ◇ ◆ ❦ ❧ ☙ ❥ ⚜ ༺ ༻ ꧁ ꧂ ꕥ ꕤ ⸙ ⫷ ⫸ 〘 〙 『 』 「 」 【 】 〖 〗 《 》 ⟦ ⟧ ⦅ ⦆ ⟪ ⟫ ⟨ ⟩ 〔 〕 ⌜ ⌝ ⌞ ⌟ ⦗ ⦘ ❨ ❩ ❪ ❫ ❬ ❭ ❮ ❯ ❰ ❱ ❲ ❳ ❴ ❵ ꒰ ꒱ ‿ ⁀ ⁔ ﹏ ︵ ︶ ︷ ︸ ︹ ︺ ︻ ︼ ︽ ︾ ⊱ ⊰ ⋆ ˚ ｡ ﾟ ✩ ⭒ ⭑ ⟡ ⌘ ❈ ❇ ❆ ❅ ✻ ✼ ❄ ⚘ ☘ 🌹 🌸 🌷 💮 🏵 ⚚ ♰ ♱ ☥ ⚝ ⛤ ⛥ ⛦ ⛧ ✡ ☸ ☫ ☬ ჻ ᯽ ꔛ ꗃ ꘏ ꙰ ꣸ ꣺ ꩜ ꫂ ꫞ ꫟ ༄ ༅ ࿐ ࿔ ࿇ ࿊ ࿋ ࿌").split(" "),
            ("★ ☆ ✪ ✫ ✬ ✭ ✮ ✯ ✰ ⭐ 🌟 ✨ ✓ ✔ ☑ ✅ ✗ ✘ ☒ ❌ ❎ ♡ ♥ ❤ ❣ ღ 💔 ♠ ♣ ♦ ♧ ♤ ♢ ➤ ➜ ➔ ➙ ➛ ➝ ➞ ➟ ➠ ➡ ⬅ ⬆ ⬇ ← → ↑ ↓ ↔ ↕ ↖ ↗ ↘ ↙ ⇦ ⇨ ⇧ ⇩ ⇐ ⇒ ⇑ ⇓ ⇔ ⇄ ⇆ ↩ ↪ ⟵ ⟶ ⟷ ⤴ ⤵ ↻ ↺ ⟳ ⟲ ➥ ➦ ➧ ➨ ➩ ➪ ➫ ➬ ➭ ➮ ➯ ➱ ➲ ➳ ➵ ➸ ➺ ➻ ➼ ➽ ➾ « » ‹ › “ ” ‘ ’ „ ‚ • ◦ ∙ · ● ○ ◉ ◎ ◌ ◍ ◐ ◑ ◒ ◓ ■ □ ▪ ▫ ▢ ▣ ▤ ▥ ▦ ▧ ▨ ▩ ▲ △ ▼ ▽ ◀ ▶ ◁ ▷ ◢ ◣ ◤ ◥ ◊ ⬟ ⬢ ⬡ ° ∞ ≈ ≠ ± ∓ ÷ × √ ∛ ∜ ∑ ∏ ∆ ∇ π Ω µ ∫ ∬ ∮ ≤ ≥ ≪ ≫ ≡ ≅ ∝ ∂ ∈ ∉ ∋ ∩ ∪ ⊂ ⊃ ⊆ ⊇ ⊕ ⊗ ⊥ ∠ ∟ ∧ ∨ ¬ ∀ ∃ ∅ ∴ ∵ ∶ ∷ ‰ ‱ ½ ⅓ ⅔ ¼ ¾ ⅕ ⅛ ⁰ ¹ ² ³ ⁴ ⁵ ⁶ ⁷ ⁸ ⁹ ⁺ ⁻ ⁼ ⁽ ⁾ ₀ ₁ ₂ ₃ ₄ ₅ ₆ ₇ ₈ ₉ α β γ δ ε θ λ σ φ ψ ω ™ © ® ℠ ℗ № § ¶ † ‡ ‼ ⁉ ⁇ ⁈ ¿ ¡ … ‐ – — ― ¦ ‖ $ € £ ¥ ¢ ₹ ₽ ₩ ₺ ₿ ₦ ₱ ₴ ₫ ☀ ☁ ☂ ☔ ☃ ⛄ ☄ ⚡ ⛅ ⛈ ☼ ♨ ☕ ⌚ ⌛ ⏳ ⏰ ☎ ✆ ✉ ✂ ✄ ✎ ✏ ✐ ✒ ⌨ ⚙ ⚖ ⚓ ⚔ ⚒ ⛏ ⚠ ☢ ☣ ♻ ⚛ ☮ ☯ ⚕ ♿ ⚑ ⚐ ⛳ ⚽ ⚾ ⛵ ✈ ⛺ ♈ ♉ ♊ ♋ ♌ ♍ ♎ ♏ ♐ ♑ ♒ ♓ ♩ ♪ ♫ ♬ ♭ ♮ ♯ ♔ ♕ ♖ ♗ ♘ ♙ ♚ ♛ ♜ ♝ ♞ ♟ ⚀ ⚁ ⚂ ⚃ ⚄ ⚅ ☺ ☻ ☹ ✌ ☝ ✍ ✋ ♂ ♀ ⚥ ⚢ ⚣ 웃 유 ツ ㋡ ① ② ③ ④ ⑤ ⑥ ⑦ ⑧ ⑨ ⑩ ⑪ ⑫ ⑬ ⑭ ⑮ ⑯ ⑰ ⑱ ⑲ ⑳ ❶ ❷ ❸ ❹ ❺ ❻ ❼ ❽ ❾ ❿ ⑴ ⑵ ⑶ ⑷ ⑸ ⑹ ⑺ ⑻ ⑼ ⑽ Ⅰ Ⅱ Ⅲ Ⅳ Ⅴ Ⅵ Ⅶ Ⅷ Ⅸ Ⅹ Ⅺ Ⅻ ⌂ ⌐ ⌗ ⌬ ⏎ ⏏ ⎋ ⌫ ⌦ ⏻ ⏼ ⏽ ⏸ ⏹ ⏺ ⏩ ⏪ ⏫ ⏬ ⏭ ⏮ ⏯ ▓ ▒ ░ █ ▄ ▀ ▌ ▐ ─ │ ┌ ┐ └ ┘ ├ ┤ ┬ ┴ ┼ ═ ║ ╔ ╗ ╚ ╝ ╠ ╣ ╦ ╩ ╬ ╭ ╮ ╯ ╰ ╱ ╲ ╳ ⓐ ⓑ ⓒ ⓓ ⓔ ⓕ ⓖ ⓗ ⓘ ⓙ ⓚ ⓛ ⓜ ⓝ ⓞ ⓟ ⓠ ⓡ ⓢ ⓣ ⓤ ⓥ ⓦ ⓧ ⓨ ⓩ ⒜ ⒝ ⒞ ⒟ ⒠ ⒡ ⒢ ⒣ ⒤ ⒥ ⓫ ⓬ ⓭ ⓮ ⓯ ⓰ ⓱ ⓲ ⓳ ⓴ ⓵ ⓶ ⓷ ⓸ ⓹ ⓺ ⓻ ⓼ ⓽ ⓾ ➀ ➁ ➂ ➃ ➄ ➅ ➆ ➇ ➈ ➉ ➊ ➋ ➌ ➍ ➎ ➏ ➐ ➑ ➒ ➓ ⒈ ⒉ ⒊ ⒋ ⒌ ⒍ ⒎ ⒏ ⒐ ⒑ ✁ ✃ ✇ ✑ ✕ ✖ ✙ ✚ ✛ ✜ ✝ ✞ ✟ ✠ ✢ ✣ ✤ ✥ ❍ ❏ ❐ ❑ ❒ ❘ ❙ ❚ ➢ ➣ ➚ ➘ ➹ ➶ ➷ ⇝ ⇜ ↬ ↫ ⟿ ↜ ↝ ↞ ↠ ↢ ↣ ↤ ↦ ↯ ↰ ↱ ↲ ↳ ↴ ↵ ⇉ ⇇ ⇈ ⇊ ⇋ ⇌ ⇎ ⇏ ⇍ ⇕ ⇖ ⇗ ⇘ ⇙ ⇚ ⇛ ⇞ ⇟ ⇠ ⇡ ⇢ ⇣ ⇤ ⇥ ⇪ ⌥ ⌃ ⎈ ⌀ ⌁ ⌇ ⌒ ⌓ ⌔ ⌖ ⍟ ⍣ ⍤ ⍥ ⍨ ⎊ ⎔ ⏣ ⏢ ⏥ ⬠ ⬣ ⬤ ⬥ ⬦ ⬧ ⬨ ⬩ ⬪ ⬫ ⬬ ⬭ ⬮ ⬯ ◘ ◙ ◚ ◛ ◜ ◝ ◞ ◟ ◠ ◡ ◧ ◨ ◩ ◪ ◫ ◬ ◭ ◮ ◰ ◱ ◲ ◳ ◴ ◵ ◶ ◷ ☖ ☗ ☚ ☛ ☜ ☞ ☟ ☡ ☤ ☦ ☧ ☨ ☩ ☰ ☱ ☲ ☳ ☴ ☵ ☶ ☷ ☿ ♁ ♃ ♄ ♅ ♆ ♇ ⚈ ⚉ ⚆ ⚇ ⚲ ⚳ ⚴ ⚵ ⚶ ⚷ ⚸ ⛋ ⛌ ⛍ ⛎ ⛐ ⛑ ⛒ ⛓ ⛔ ⛕ ⛖ ⛗ ⛘ ⛙ ⛚ ⛛ ⛜ ⛝ ⛞ ⛟ ⛠ ⛡ ⛩ ⛪ ⛫ ⛬ ⛭ ⛮ ⛯ ⛰ ⛱ ⛲ ⛴ ⛶ ⛷ ⛸ ⛹ ⛻ ⛼ ⛽ ⛾ ⛿").split(" ")
    };
}
