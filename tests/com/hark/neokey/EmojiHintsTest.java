package com.hark.neokey;

/** Roadmap Phase 0 / Week 2: emoji hints for common words (word -> emoji map). */
public class EmojiHintsTest {
    static int pass, fail;
    static void check(String name, boolean ok) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "PASS " : "FAIL ") + name);
    }

    static void eq(String word, String emoji) {
        check(word + " -> " + emoji, emoji.equals(EmojiHints.forWord(word)));
    }

    public static void run() {
        System.out.println("== EmojiHints:arabic words ==");
        eq("قهوة", "☕"); eq("شاي", "☕");
        eq("احبك", "❤️"); eq("أحبك", "❤️"); eq("حبيبي", "❤️"); eq("قلبي", "❤️");
        eq("هههه", "😂"); eq("هههههه", "😂"); eq("ضحك", "😂");
        eq("جميل", "😍"); eq("روعة", "😍");
        eq("تمام", "👍"); eq("طيب", "👍");
        eq("شكرا", "🙏"); eq("شكراً", "🙏"); eq("مشكور", "🙏");
        // data note: "please" is mapped twice in the source (praying hands, then pleading face) —
        // HashMap last-wins resolves it to 🥺; flagged as a Week-2 data nit in the report.
        eq("يارب", "🤲"); eq("آمين", "🤲");
        eq("ورد", "🌹"); eq("وردة", "🌹");
        eq("مبروك", "🎉"); eq("مبارك", "🎉");
        eq("مكة", "🕋"); eq("عمرة", "🕋");
        eq("رمضان", "🌙"); eq("مساء", "🌙");
        eq("سيارة", "🚗"); eq("طريق", "🚗");
        eq("سفر", "✈️"); eq("مطار", "✈️");
        eq("اكيد", "💯"); eq("أكيد", "💯"); eq("بالضبط", "💯");
        eq("كشخة", "😎");
        eq("تم", "✅"); eq("خلاص", "✅");
        eq("وقت", "⏰"); eq("ساعة", "⏰");
        eq("بيت", "🏠"); eq("البيت", "🏠");
        eq("دوام", "💼"); eq("شغل", "💼");
        eq("دراسة", "📚"); eq("جامعة", "📚");
        eq("مطر", "🌧️");
        eq("بطل", "💪"); eq("قوة", "💪");
        eq("نار", "🔥"); eq("قوي", "🔥");

        System.out.println("== EmojiHints:english words ==");
        eq("love", "❤️"); eq("heart", "❤️");
        eq("lol", "😂"); eq("haha", "😂"); eq("funny", "😂");
        eq("beautiful", "😍"); eq("amazing", "😍");
        eq("ok", "👍"); eq("great", "👍");
        eq("thanks", "🙏"); eq("please", "🥺");   // duplicate-keyword last-wins (data nit, see Week-2 report)
        eq("coffee", "☕"); eq("tea", "☕");
        eq("birthday", "🎂"); eq("cake", "🎂");
        eq("morning", "☀️"); eq("sun", "☀️");
        eq("night", "🌙"); eq("moon", "🌙");
        eq("sleep", "😴"); eq("tired", "😴");
        eq("food", "🍔"); eq("hungry", "🍔");
        eq("car", "🚗"); eq("drive", "🚗");
        eq("travel", "✈️"); eq("flight", "✈️");
        eq("football", "⚽"); eq("soccer", "⚽");
        eq("fire", "🔥"); eq("hot", "🔥");
        eq("exactly", "💯"); eq("sure", "💯");
        eq("cool", "😎");
        eq("think", "🤔"); eq("maybe", "🤔"); eq("hmm", "🤔");
        eq("hello", "👋"); eq("hi", "👋"); eq("bye", "👋");
        eq("strong", "💪"); eq("gym", "💪");
        eq("mosque", "🕌");
        eq("call", "📞"); eq("phone", "📞");
        eq("money", "💰"); eq("cash", "💰");
        eq("gift", "🎁");
        eq("kiss", "😘");
        eq("done", "✅");
        eq("time", "⏰");
        eq("home", "🏠"); eq("house", "🏠");
        eq("work", "💼"); eq("job", "💼");
        eq("study", "📚"); eq("school", "📚");
        eq("rain", "🌧️");

        System.out.println("== EmojiHints:robustness ==");
        check("tashkeel word maps", "❤️".equals(EmojiHints.forWord("حَبيبتي")));
        check("hamza folded both ways", EmojiHints.forWord("احبك").equals(EmojiHints.forWord("أحبك")));
        check("unknown word null", EmojiHints.forWord("زقزقنة_غيرموجودة") == null);
        check("empty null", EmojiHints.forWord("") == null);
        check("null null", EmojiHints.forWord(null) == null);
        System.out.println("EmojiHintsTest subtotal: " + pass + " passed, " + fail + " failed");
    }
}
