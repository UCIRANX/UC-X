package ir.uciran.uctor

/** کشورهای قابل‌انتخاب برای IP خروجی (exit). code همان کد دو‌حرفی ISO است که Tor می‌شناسد. */
data class ExitCountry(val code: String, val name: String) {
    val flag: String
        get() = code.uppercase().map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
}

object ExitCountries {
    val all = listOf(
        ExitCountry("de", "آلمان"), ExitCountry("nl", "هلند"), ExitCountry("us", "آمریکا"),
        ExitCountry("fr", "فرانسه"), ExitCountry("gb", "بریتانیا"), ExitCountry("se", "سوئد"),
        ExitCountry("ch", "سوئیس"), ExitCountry("ca", "کانادا"), ExitCountry("fi", "فنلاند"),
        ExitCountry("at", "اتریش"), ExitCountry("no", "نروژ"), ExitCountry("is", "ایسلند"),
        ExitCountry("ie", "ایرلند"), ExitCountry("lu", "لوکزامبورگ"), ExitCountry("ro", "رومانی"),
        ExitCountry("bg", "بلغارستان"), ExitCountry("cz", "چک"), ExitCountry("pl", "لهستان"),
        ExitCountry("md", "مولداوی"), ExitCountry("ua", "اوکراین"), ExitCountry("es", "اسپانیا"),
        ExitCountry("it", "ایتالیا"), ExitCountry("jp", "ژاپن"), ExitCountry("sg", "سنگاپور"),
        ExitCountry("hk", "هنگ‌کنگ"), ExitCountry("au", "استرالیا"), ExitCountry("br", "برزیل"),
    )

    fun find(code: String?): ExitCountry? = all.firstOrNull { it.code.equals(code?.trim(), ignoreCase = true) }
}
