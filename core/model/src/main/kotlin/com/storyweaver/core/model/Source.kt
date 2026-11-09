package com.storyweaver.core.model

enum class SourceType {
    AUTHOR_WORKS,
    SERIES,
    STORY,
    UNKNOWN,
}

enum class SourceValidation {
    VALID,
    UNSUPPORTED_HOST,
    UNSUPPORTED_PATH,
    INVALID_URL,
    INSECURE_SCHEME,
}

data class SourceUrl(
    val submitted: String,
    val canonical: String?,
    val sourceType: SourceType,
    val validation: SourceValidation,
    val message: String? = null,
)

enum class WorkType {
    STANDALONE,
    SERIES,
}
