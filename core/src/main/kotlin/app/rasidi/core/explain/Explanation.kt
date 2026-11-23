package app.rasidi.core.explain

/** «كيف انحسب؟»: القيمة، المعادلة، والبنود مع أسعارها ومصادرها وتواريخها والحركات المرتبطة. */
data class Explanation(
    val title: String,
    val result: String,
    val formula: String,
    val items: List<Item>,
    val pending: List<String> = emptyList(),
    val asOf: String? = null,
) {
    data class Item(
        val label: String,
        val original: String,
        val rate: String? = null,
        val rateSource: String? = null,
        val rateDate: String? = null,
        val value: String? = null,
        val note: String? = null,
        val refIds: List<String> = emptyList(),
    )
}
