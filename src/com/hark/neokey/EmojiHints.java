package com.hark.neokey;

import java.util.HashMap;
import java.util.Map;

/** Suggests an emoji for common Arabic / English words. */
final class EmojiHints {
    private static final Map<String, String> MAP = new HashMap<>();

    private static void add(String emoji, String... words) {
        for (String w : words) MAP.put(Dictionary.norm(w), emoji);
    }

    static {
        add("❤️", "حب", "احبك", "أحبك", "حبيبي", "حبيبتي", "قلبي", "love", "heart");
        add("😂", "هههه", "ههههه", "هههههه", "ضحك", "lol", "haha", "hahaha", "funny");
        add("😍", "جميل", "حلو", "روعة", "رائع", "beautiful", "amazing", "wow");
        add("👍", "تمام", "اوكي", "طيب", "ok", "okay", "good", "great");
        add("🙏", "شكرا", "شكراً", "مشكور", "يعطيك", "thanks", "thank", "please");
        add("🤲", "الله", "اللهم", "يارب", "آمين", "امين", "دعاء");
        add("🌹", "ورد", "وردة", "rose", "flower");
        add("☕", "قهوة", "شاي", "coffee", "tea");
        add("😢", "حزين", "زعلان", "sad", "cry");
        add("😡", "معصب", "زعل", "angry");
        add("🎉", "مبروك", "مبارك", "عيد", "congrats", "party", "celebrate");
        add("🎂", "ميلاد", "birthday", "cake");
        add("☀️", "صباح", "شمس", "morning", "sun");
        add("🌙", "مساء", "ليل", "رمضان", "night", "moon");
        add("😴", "نوم", "نعسان", "sleep", "tired");
        add("🍔", "اكل", "جوعان", "غدا", "عشا", "food", "hungry", "lunch", "dinner");
        add("🚗", "سيارة", "طريق", "car", "drive");
        add("✈️", "سفر", "طيارة", "مطار", "travel", "flight");
        add("⚽", "كورة", "مباراة", "هلال", "نصر", "اتحاد", "football", "soccer", "match");
        add("🔥", "نار", "قوي", "fire", "lit", "hot");
        add("💯", "اكيد", "أكيد", "بالضبط", "exactly", "sure");
        add("😎", "كشخة", "cool");
        add("🤔", "يمكن", "ممكن", "think", "maybe", "hmm");
        add("👋", "هلا", "مرحبا", "اهلا", "أهلا", "hi", "hello", "hey", "bye");
        add("💪", "بطل", "قوة", "strong", "gym");
        add("🕋", "مكة", "عمرة", "حج", "كعبة");
        add("🕌", "مسجد", "صلاة", "المدينة", "جمعة", "mosque");
        add("📞", "اتصل", "كلمني", "call", "phone");
        add("💰", "فلوس", "راتب", "money", "cash");
        add("🎁", "هدية", "gift");
        add("😘", "بوسة", "kiss");
        add("🥺", "لطيف", "please");
        add("✅", "تم", "خلاص", "done");
        add("⏰", "وقت", "ساعة", "time");
        add("🏠", "بيت", "البيت", "home", "house");
        add("💼", "دوام", "شغل", "عمل", "work", "job");
        add("📚", "دراسة", "مدرسة", "جامعة", "study", "school");
        add("🌧️", "مطر", "rain");
    }

    static String forWord(String w) {
        if (w == null || w.isEmpty()) return null;
        return MAP.get(Dictionary.norm(w));
    }
}
