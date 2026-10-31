package org.akkirrai.hibiki.feature.settings

import org.akkirrai.beakokit.api.SourceId
import org.akkirrai.beakokit.api.SourceInfo
import org.akkirrai.beakokit.api.SourceLanguage
import org.akkirrai.hibiki.core.source.AnimeSourceDescriptor
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceLanguageGroupingTest {
    @Test
    fun `sections use primary language without duplicating multilingual sources`() {
        val persian = descriptor("fa", setOf(SourceLanguage.PERSIAN), SourceLanguage.PERSIAN)
        val english = descriptor("en", setOf(SourceLanguage.ENGLISH), SourceLanguage.ENGLISH)
        val localizedPersian = descriptor(
            id = "fa-localized",
            languages = setOf(SourceLanguage.PERSIAN, SourceLanguage.ENGLISH),
            primaryLanguage = SourceLanguage.PERSIAN,
        )

        val grouped = groupSourcesByLanguage(listOf(persian, english, localizedPersian))

        assertEquals(listOf(persian, localizedPersian), grouped[SourceLanguage.PERSIAN])
        assertEquals(listOf(english), grouped[SourceLanguage.ENGLISH])
    }

    private fun descriptor(
        id: String,
        languages: Set<SourceLanguage>,
        primaryLanguage: SourceLanguage,
    ) = AnimeSourceDescriptor(
        info = SourceInfo(
            id = SourceId(id),
            name = id,
            languages = languages,
            primaryLanguage = primaryLanguage,
        ),
        iconRes = 0,
    )
}
