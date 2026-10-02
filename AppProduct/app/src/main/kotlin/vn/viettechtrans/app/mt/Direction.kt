package vn.viettechtrans.app.mt

/** Languages supported by the app. [code] matches Core's language codes. */
enum class Lang(val code: String) {
    VI("vi"),
    EN("en"),
}

/**
 * Translation direction. [id] is the only form ever persisted (history, evidence,
 * MT package manifest) and equals Core's `contracts.Direction` values.
 */
enum class Direction(val id: String, val source: Lang, val target: Lang) {
    EN_TO_VI("en_to_vi", Lang.EN, Lang.VI),
    VI_TO_EN("vi_to_en", Lang.VI, Lang.EN),
    ;

    val reverse: Direction
        get() = if (this == EN_TO_VI) VI_TO_EN else EN_TO_VI

    companion object {
        fun fromId(id: String): Direction =
            entries.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("Unknown direction id: $id")

        /** Direction whose source language is [source]. */
        fun fromSource(source: Lang): Direction =
            if (source == Lang.EN) EN_TO_VI else VI_TO_EN
    }
}
