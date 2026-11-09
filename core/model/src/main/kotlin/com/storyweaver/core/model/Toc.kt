package com.storyweaver.core.model

enum class TocNodeKind {
    AUTHOR,
    STANDALONE,
    SERIES,
    CHAPTER,
    IMPORTED_BOOK,
    IMPORTED_SECTION,
}

data class TocNode(
    val id: TocNodeId,
    val parentId: TocNodeId?,
    val kind: TocNodeKind,
    val referencedEntityId: String,
    val displayTitle: String,
    val position: Int,
    val selected: Boolean,
    val lockedChildren: Boolean = false,
)

data class CollectionDraft(
    val id: CollectionId,
    val title: String,
    val subtitle: String? = null,
    val curator: String? = null,
    val exportMode: ExportMode = ExportMode.COMBINED,
    val epubStyle: EpubStyle = EpubStyle.MODERN,
    val accentColorArgb: Long = 0xFF7A2E3D,
    val nodes: List<TocNode> = emptyList(),
)

enum class ExportMode {
    COMBINED,
    SEPARATE,
}

enum class EpubStyle {
    MODERN,
    CLASSIC,
    MINIMAL,
}

enum class JobPhase {
    DRAFT,
    ANALYZING,
    READY,
    DOWNLOADING,
    WAITING_FOR_DECISION,
    BUILDING,
    VALIDATING,
    COMPLETE,
    FAILED,
}

data class ExportSnapshot(
    val collectionId: CollectionId,
    val title: String,
    val subtitle: String?,
    val curator: String?,
    val exportMode: ExportMode,
    val epubStyle: EpubStyle,
    val accentColorArgb: Long,
    val selectedChapters: List<Chapter>,
    val selectedWorks: List<Work>,
    val authors: List<Author>,
    val chapterContents: Map<ChapterId, ChapterContent> = emptyMap(),
)
