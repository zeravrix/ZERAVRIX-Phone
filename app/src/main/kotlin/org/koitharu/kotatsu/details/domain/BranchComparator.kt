package org.zeravrix.kotatsu.details.domain

import org.zeravrix.kotatsu.core.util.LocaleStringComparator
import org.zeravrix.kotatsu.details.ui.model.MangaBranch

class BranchComparator : Comparator<MangaBranch> {

	private val delegate = LocaleStringComparator()

	override fun compare(o1: MangaBranch, o2: MangaBranch): Int = delegate.compare(o1.name, o2.name)
}
