package com.hark.neokey;

/** Offline professional message templates (Arabic + English). Every result is a draft the user edits. */
final class Writer {
    static final String[] CATS = {"رسمية", "خدمة العملاء", "عمل", "توظيف", "ردود مختصرة", "اعتذار", "شكر"};
    static final String[] TONES = {"رسمي", "مهني ودّي", "مختصر"};
    static final String[] LANGS = {"عربي", "English"};
    static final int SHORT = 4;

    private Writer() {}

    private static final String[][] AR = {
            { // formal
                    "أتقدم إليكم بهذا الخطاب بخصوص {t}، راجياً التكرم بالاطلاع عليه واتخاذ ما ترونه مناسباً حياله. وأنا على استعداد لتزويدكم بأي معلومات إضافية.",
                    "إشارةً إلى {t}، أود إفادتكم برغبتنا في متابعة هذا الموضوع. ونأمل التكرم بإفادتنا بالإجراءات اللازمة والمدة المتوقعة لإنجازه.",
                    "يسرني أن أرفع إليكم طلبي المتعلق بـ{t}، آملاً النظر فيه بعين الاعتبار. وقد أرفقت المستندات المطلوبة لتسهيل دراسته.",
            },
            { // customer service
                    "شكراً لتواصلك معنا بخصوص {t}. نعتذر عن أي إزعاج سببه ذلك، وقد رفعنا طلبك للفريق المختص وسنوافيك بالتحديث خلال ٢٤ ساعة.",
                    "نقدّر صبرك وتواصلك معنا. بخصوص {t}، تحققنا من طلبك والمشكلة قيد المعالجة الآن. إن احتجت أي مساعدة إضافية فنحن هنا لخدمتك.",
                    "شكراً على ملاحظتك حول {t}. لمساعدتك بشكل أسرع، نرجو تزويدنا برقم الطلب وصورة توضح المشكلة إن أمكن.",
            },
            { // business
                    "أود متابعة موضوع {t}. هل يمكننا تحديد اجتماع قصير هذا الأسبوع لمناقشة الخطوات القادمة؟ يناسبني أي وقت يناسبكم.",
                    "إليكم تحديثاً بخصوص {t}: أُنجزت المرحلة الحالية، والخطوة التالية تحتاج إلى اعتمادكم. أرجو موافاتي بملاحظاتكم.",
                    "بالإشارة إلى {t}، نرجو تأكيد استلامكم لهذه الرسالة وإفادتنا بالموعد المتوقع للتنفيذ.",
            },
            { // job
                    "أتقدم بطلب التوظيف لوظيفة {t} لديكم. أمتلك خبرة عملية ومهارات تتوافق مع متطلبات الوظيفة، وأتطلع إلى الانضمام لفريقكم والمساهمة في نجاحه. أرفقت سيرتي الذاتية للاطلاع.",
                    "اطلعت باهتمام على إعلانكم عن وظيفة {t}، وأرى أن خبراتي ومهاراتي تؤهلني لهذا الدور. يسعدني إجراء مقابلة في الوقت الذي يناسبكم.",
                    "أود الاستفسار عن حالة طلبي لوظيفة {t} الذي تقدمت به مؤخراً، وأؤكد اهتمامي الكبير بالانضمام إليكم.",
            },
            { // short replies
                    "تم، شكراً لك.", "وصلت، وسأرد عليك قريباً إن شاء الله.", "تمام، موافق.",
                    "أعتذر، لا أستطيع حالياً، وسأعود إليك لاحقاً.", "ممتاز، نلتقي على الموعد.", "شكراً على التوضيح.",
                    "سأراجع الموضوع وأفيدك.", "أنا في اجتماع الآن، سأتواصل معك بعد قليل.",
            },
            { // apology
                    "أعتذر بصدق عن {t}، وأتحمل المسؤولية كاملة. سأحرص على ألا يتكرر ذلك، وأقدّر تفهمك.",
                    "تقبّل اعتذاري عن {t}؛ لم يكن ذلك مقصوداً. اتخذت الإجراءات اللازمة لتصحيح الوضع، وأشكرك على سعة صدرك.",
                    "نعتذر عن {t} وما سببه من إزعاج. يهمنا رضاك، وقد عملنا على معالجة الأمر فوراً.",
            },
            { // thanks
                    "أتقدم بخالص الشكر والتقدير على {t}. كان لدعمكم أثر كبير، وأتطلع لمواصلة التعاون معكم.",
                    "شكراً جزيلاً على {t}؛ أقدّر وقتك واهتمامك كثيراً.",
                    "لا يسعني إلا أن أشكركم على {t}، وجزاكم الله خيراً.",
            },
    };

    private static final String[][] EN = {
            {
                    "I am writing to you regarding {t}. I would be grateful if you could review this matter and take the action you deem appropriate. I am happy to provide any further information.",
                    "With reference to {t}, we would like to follow up on this matter. Kindly advise us of the required procedures and the expected timeline.",
                    "I am pleased to submit my request concerning {t} for your kind consideration. The required documents are attached for your review.",
            },
            {
                    "Thank you for contacting us about {t}. We apologise for any inconvenience. Your request has been escalated to the relevant team and we will update you within 24 hours.",
                    "We appreciate your patience. Regarding {t}, we have reviewed your request and the issue is now being resolved. If you need anything else, we are here to help.",
                    "Thank you for your feedback about {t}. To help you faster, please share your order number and a screenshot of the issue if possible.",
            },
            {
                    "I would like to follow up on {t}. Could we schedule a short meeting this week to discuss next steps? I am flexible with timing.",
                    "Here is an update on {t}: the current phase is complete and the next step needs your approval. I look forward to your feedback.",
                    "With reference to {t}, please confirm receipt of this message and let us know the expected delivery date.",
            },
            {
                    "I am applying for the {t} position. My experience and skills match the role's requirements, and I look forward to contributing to your team. Please find my CV attached.",
                    "I read your {t} vacancy with great interest and believe my background makes me a strong fit. I would welcome the chance to interview at your convenience.",
                    "I am writing to ask about the status of my recent application for the {t} position and to confirm my strong interest in joining your team.",
            },
            {
                    "Done, thank you.", "Received, I will get back to you shortly.", "Sounds good, agreed.",
                    "Sorry, I can't right now. I will get back to you later.", "Great, see you then.", "Thanks for clarifying.",
                    "I will look into it and let you know.", "I'm in a meeting, I will call you shortly.",
            },
            {
                    "I sincerely apologise for {t} and take full responsibility. I will make sure it does not happen again, and I appreciate your understanding.",
                    "Please accept my apology for {t}; it was not intended. I have taken steps to correct it, and thank you for your patience.",
                    "We apologise for {t} and any inconvenience it caused. Your satisfaction matters to us and we have addressed the issue right away.",
            },
            {
                    "I would like to express my sincere thanks for {t}. Your support made a real difference, and I look forward to continuing our work together.",
                    "Thank you so much for {t}; I truly appreciate your time and care.",
                    "I cannot thank you enough for {t}. It is greatly appreciated.",
            },
    };

    static int variants(int cat, int lang) { return (lang == 1 ? EN : AR)[cat].length; }

    static String[] shortReplies(int lang) { return (lang == 1 ? EN : AR)[SHORT]; }

    /** Builds a draft. Empty fields become [brackets] so the user sees what to fill in. */
    static String compose(int cat, int tone, int lang, String to, String from, String topic, int variant) {
        boolean en = lang == 1;
        String[] set = (en ? EN : AR)[cat];
        String body = set[((variant % set.length) + set.length) % set.length];
        if (cat == SHORT) return body;
        String t = topic == null || topic.trim().isEmpty() ? (en ? "[subject]" : "[الموضوع]") : topic.trim();
        body = body.replace("{t}", t);
        if (tone == 2) body = firstSentence(body, en);
        to = to == null ? "" : to.trim();
        from = from == null ? "" : from.trim();
        StringBuilder b = new StringBuilder();
        if ((cat == 0 || cat == 2 || cat == 3) && tone != 2) b.append(en ? "Subject: " : "الموضوع: ").append(subject(cat, t, en)).append("\n\n");
        b.append(greeting(tone, en, to, cat)).append("\n\n").append(body).append("\n\n").append(closing(tone, en, from, cat));
        return b.toString();
    }

    private static String subject(int cat, String t, boolean en) {
        if (cat == 3) return en ? "Application for " + t : "طلب توظيف - " + t;
        return t;
    }

    private static String greeting(int tone, boolean en, String to, int cat) {
        if (en) {
            if (tone == 0) return to.isEmpty() ? "Dear Sir or Madam," : "Dear " + to + ",";
            if (tone == 1) return (to.isEmpty() ? "Hello," : "Hi " + to + ",") + (cat == 1 ? "" : "\nI hope you are well.");
            return to.isEmpty() ? "Hi," : "Hi " + to + ",";
        }
        if (tone == 0) return (to.isEmpty() ? "" : "سعادة/ " + to + " المحترم\n") + "السلام عليكم ورحمة الله وبركاته، وبعد:";
        if (tone == 1) return (to.isEmpty() ? "مرحباً،" : "مرحباً " + to + "،") + (cat == 1 ? "" : "\nأتمنى أن تكون بخير.");
        return to.isEmpty() ? "أهلاً،" : "أهلاً " + to + "،";
    }

    private static String closing(int tone, boolean en, String from, int cat) {
        String sign = from.isEmpty() ? (en ? "[Your name]" : "[اسمك]") : from;
        if (cat == 1) sign = from.isEmpty() ? (en ? "Customer Care Team" : "فريق خدمة العملاء") : from;
        if (en) return (tone == 0 ? "Yours faithfully," : tone == 1 ? "Kind regards," : "Thanks,") + "\n" + sign;
        return (tone == 0 ? "وتفضلوا بقبول فائق الاحترام والتقدير،" : tone == 1 ? "مع خالص التحية،" : "شكراً لك،") + "\n" + sign;
    }

    private static String firstSentence(String s, boolean en) {
        int i = s.indexOf(". ");
        if (i < 0) i = s.indexOf(".");
        return i > 0 ? s.substring(0, i + 1) : s;
    }
}
