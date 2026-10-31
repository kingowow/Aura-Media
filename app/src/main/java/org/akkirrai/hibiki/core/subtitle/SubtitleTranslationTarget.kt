package org.akkirrai.hibiki.core.subtitle

/**
 * Languages the subtitle translator can target, shown under Settings → Preferences.
 *
 * [code]         Google / Edge / Azure language code sent to the translation endpoint.
 * [deeplCode]    DeepL target-language code (differs from Google for some languages).
 *                Null means DeepL does not support this language — engine falls back to Google.
 * [label]        Language name in its own script (no localization needed).
 *
 * [PERSIAN] stays first — it was the only option in the original app.
 */
enum class SubtitleTranslationTarget(
    val code: String,
    val deeplCode: String?,
    val label: String,
) {
    // ── Most used ────────────────────────────────────────────────────────────
    PERSIAN("fa", null, "فارسی"),
    ENGLISH("en", "EN-US", "English"),
    ARABIC("ar", null, "العربية"),
    TURKISH("tr", "TR", "Türkçe"),
    URDU("ur", null, "اردو"),
    RUSSIAN("ru", "RU", "Русский"),

    // ── Asia ─────────────────────────────────────────────────────────────────
    CHINESE_SIMPLIFIED("zh", "ZH-HANS", "简体中文"),
    CHINESE_TRADITIONAL("zh-TW", "ZH-HANT", "繁體中文"),
    JAPANESE("ja", "JA", "日本語"),
    KOREAN("ko", "KO", "한국어"),
    HINDI("hi", null, "हिन्दी"),
    INDONESIAN("id", "ID", "Bahasa Indonesia"),
    VIETNAMESE("vi", null, "Tiếng Việt"),
    THAI("th", null, "ภาษาไทย"),
    MALAY("ms", null, "Bahasa Melayu"),
    BENGALI("bn", null, "বাংলা"),
    AZERBAIJANI("az", null, "Azərbaycan"),
    KAZAKH("kk", null, "Қазақша"),
    UZBEK("uz", null, "O'zbek"),

    // ── Europe ───────────────────────────────────────────────────────────────
    SPANISH("es", "ES", "Español"),
    FRENCH("fr", "FR", "Français"),
    GERMAN("de", "DE", "Deutsch"),
    PORTUGUESE_BR("pt", "PT-BR", "Português (Brasil)"),
    PORTUGUESE_PT("pt-PT", "PT-PT", "Português (Portugal)"),
    ITALIAN("it", "IT", "Italiano"),
    DUTCH("nl", "NL", "Nederlands"),
    POLISH("pl", "PL", "Polski"),
    UKRAINIAN("uk", "UK", "Українська"),
    SWEDISH("sv", "SV", "Svenska"),
    NORWEGIAN("no", null, "Norsk"),
    DANISH("da", "DA", "Dansk"),
    FINNISH("fi", "FI", "Suomi"),
    GREEK("el", "EL", "Ελληνικά"),
    CZECH("cs", "CS", "Čeština"),
    HUNGARIAN("hu", "HU", "Magyar"),
    ROMANIAN("ro", "RO", "Română"),
    BULGARIAN("bg", "BG", "Български"),
    CROATIAN("hr", null, "Hrvatski"),
    SLOVAK("sk", "SK", "Slovenčina"),
    LITHUANIAN("lt", "LT", "Lietuvių"),
    LATVIAN("lv", "LV", "Latviešu"),
    ESTONIAN("et", "ET", "Eesti"),
    SERBIAN("sr", null, "Српски"),

    // ── Other ────────────────────────────────────────────────────────────────
    SWAHILI("sw", null, "Kiswahili"),
    HEBREW("he", null, "עברית"),
    ARMENIAN("hy", null, "Հայերեն"),
    GEORGIAN("ka", null, "ქართული"),
    ALBANIAN("sq", null, "Shqip"),
    FILIPINO("fil", null, "Filipino"),
}
