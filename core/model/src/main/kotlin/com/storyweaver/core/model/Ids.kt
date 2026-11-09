package com.storyweaver.core.model

@JvmInline
value class AuthorId(val value: String) {
    init {
        require(value.isNotBlank()) { "AuthorId must not be blank" }
    }
}

@JvmInline
value class WorkId(val value: String) {
    init {
        require(value.isNotBlank()) { "WorkId must not be blank" }
    }
}

@JvmInline
value class ChapterId(val value: String) {
    init {
        require(value.isNotBlank()) { "ChapterId must not be blank" }
    }
}

@JvmInline
value class CollectionId(val value: String) {
    init {
        require(value.isNotBlank()) { "CollectionId must not be blank" }
    }
}

@JvmInline
value class TocNodeId(val value: String) {
    init {
        require(value.isNotBlank()) { "TocNodeId must not be blank" }
    }
}

@JvmInline
value class ImportedBookId(val value: String) {
    init {
        require(value.isNotBlank()) { "ImportedBookId must not be blank" }
    }
}
