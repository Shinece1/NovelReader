package com.dsh.novelreader.data

/** One entry of a PDF's bookmark tree, flattened for display. */
data class TocItem(
    val title: String,
    val pageIndex: Int,
    val level: Int,
)
