package org.zeravrix.kotatsu.reader.ui

import org.zeravrix.kotatsu.reader.ui.pager.ReaderPage

data class ReaderContent(
	val pages: List<ReaderPage>,
	val state: ReaderState?
)