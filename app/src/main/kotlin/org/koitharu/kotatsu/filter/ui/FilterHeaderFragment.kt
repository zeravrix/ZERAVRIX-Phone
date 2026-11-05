package org.zeravrix.kotatsu.filter.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.google.android.material.chip.Chip
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import org.zeravrix.kotatsu.core.nav.router
import org.zeravrix.kotatsu.core.ui.BaseFragment
import org.zeravrix.kotatsu.core.ui.widgets.ChipsView
import org.zeravrix.kotatsu.core.util.ext.isAnimationsEnabled
import org.zeravrix.kotatsu.core.util.ext.observe
import org.zeravrix.kotatsu.databinding.FragmentFilterHeaderBinding
import org.zeravrix.kotatsu.filter.data.PersistableFilter
import org.zeravrix.kotatsu.filter.ui.model.FilterHeaderModel
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Demographic
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.YEAR_UNKNOWN
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class FilterHeaderFragment : BaseFragment<FragmentFilterHeaderBinding>(), ChipsView.OnChipClickListener,
    ChipsView.OnChipCloseClickListener {

    @Inject
    lateinit var filterHeaderProducer: FilterHeaderProducer

    private val filter: FilterCoordinator?
        get() = runCatching { (requireActivity() as? FilterCoordinator.Owner)?.filterCoordinator }.getOrNull()

    override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentFilterHeaderBinding {
        return FragmentFilterHeaderBinding.inflate(inflater, container, false)
    }

    override fun onViewBindingCreated(binding: FragmentFilterHeaderBinding, savedInstanceState: Bundle?) {
        super.onViewBindingCreated(binding, savedInstanceState)
        binding.chipsTags.onChipClickListener = this
        binding.chipsTags.onChipCloseClickListener = this
        val currentFilter = filter
        if (currentFilter != null) {
            filterHeaderProducer.observeHeader(currentFilter)
                .flowOn(Dispatchers.Default)
                .observe(viewLifecycleOwner, ::onDataChanged)
        }
    }

    override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat = insets

    override fun onChipClick(chip: Chip, data: Any?) {
        val f = filter ?: return
        when (data) {
            is MangaTag -> f.toggleTag(data, !chip.isChecked)
            is PersistableFilter -> if (chip.isChecked) {
                f.reset()
            } else {
                f.setAdjusted(data.filter)
            }

            is String -> Unit
            null -> router.showTagsCatalogSheet(excludeMode = false)
        }
    }

    override fun onChipCloseClick(chip: Chip, data: Any?) {
        val f = filter ?: return
        when (data) {
            is String -> if (data == f.snapshot().listFilter.author) {
                f.setAuthor(null)
            } else {
                f.setQuery(null)
            }

            is ContentRating -> f.toggleContentRating(data, false)
            is Demographic -> f.toggleDemographic(data, false)
            is ContentType -> f.toggleContentType(data, false)
            is MangaState -> f.toggleState(data, false)
            is Locale -> f.setLocale(null)
            is Int -> f.setYear(YEAR_UNKNOWN)
            is IntRange -> f.setYearRange(YEAR_UNKNOWN, YEAR_UNKNOWN)
        }
    }

    private fun onDataChanged(header: FilterHeaderModel) {
        val binding = viewBinding ?: return
        val chips = header.chips
        if (chips.isEmpty()) {
            binding.chipsTags.setChips(emptyList())
            binding.root.isVisible = false
            return
        }
        binding.chipsTags.setChips(header.chips)
        binding.root.isVisible = true
        if (binding.root.context.isAnimationsEnabled) {
            binding.scrollView.smoothScrollTo(0, 0)
        } else {
            binding.scrollView.scrollTo(0, 0)
        }
    }
}
