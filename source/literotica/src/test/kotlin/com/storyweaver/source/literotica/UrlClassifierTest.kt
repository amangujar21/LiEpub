package com.storyweaver.source.literotica

import com.google.common.truth.Truth.assertThat
import com.storyweaver.core.model.SourceType
import com.storyweaver.core.model.SourceValidation
import org.junit.Test

class UrlClassifierTest {
    @Test
    fun `classifies author works urls`() {
        val r = UrlClassifier.classify("https://www.literotica.com/authors/shiprat/works/stories")
        assertThat(r.validation).isEqualTo(SourceValidation.VALID)
        assertThat(r.sourceType).isEqualTo(SourceType.AUTHOR_WORKS)
        assertThat(r.canonical).isEqualTo("https://www.literotica.com/authors/shiprat/works/stories")
    }

    @Test
    fun `normalizes author root to works stories`() {
        val r = UrlClassifier.classify("https://literotica.com/authors/shiprat")
        assertThat(r.validation).isEqualTo(SourceValidation.VALID)
        assertThat(r.canonical).isEqualTo("https://www.literotica.com/authors/shiprat/works/stories")
    }

    @Test
    fun `classifies series and story`() {
        assertThat(UrlClassifier.classify("https://www.literotica.com/series/se/123").sourceType)
            .isEqualTo(SourceType.SERIES)
        assertThat(UrlClassifier.classify("https://www.literotica.com/s/some-slug").sourceType)
            .isEqualTo(SourceType.STORY)
    }

    @Test
    fun `rejects unsupported host and path`() {
        assertThat(UrlClassifier.classify("https://evil.com/s/x").validation)
            .isEqualTo(SourceValidation.UNSUPPORTED_HOST)
        assertThat(UrlClassifier.classify("https://www.literotica.com/top").validation)
            .isEqualTo(SourceValidation.UNSUPPORTED_PATH)
    }
}
