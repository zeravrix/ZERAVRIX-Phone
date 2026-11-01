package org.zeravrix.kotatsu.settings.sources.catalog

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.chip.Chip
import dagger.hilt.android.AndroidEntryPoint
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import org.zeravrix.kotatsu.R
import org.zeravrix.kotatsu.core.model.titleResId
import org.zeravrix.kotatsu.core.nav.router
import org.zeravrix.kotatsu.core.ui.BaseActivity
import org.zeravrix.kotatsu.core.ui.list.OnListItemClickListener
import org.zeravrix.kotatsu.core.ui.util.FadingAppbarMediator
import org.zeravrix.kotatsu.core.ui.util.ReversibleActionObserver
import org.zeravrix.kotatsu.settings.sources.extensions.ExtensionsActivity
import org.zeravrix.kotatsu.core.ui.widgets.ChipsView
import org.zeravrix.kotatsu.core.ui.widgets.ChipsView.ChipModel
import org.zeravrix.kotatsu.core.util.LocaleComparator
import org.zeravrix.kotatsu.core.util.ext.getDisplayName
import org.zeravrix.kotatsu.core.util.ext.observe
import org.zeravrix.kotatsu.core.util.ext.observeEvent
import org.zeravrix.kotatsu.core.util.ext.toLocale
import org.zeravrix.kotatsu.databinding.ActivitySourcesCatalogBinding
import org.zeravrix.kotatsu.list.ui.adapter.TypedListSpacingDecoration
import org.zeravrix.kotatsu.main.ui.owners.AppBarOwner
import org.koitharu.kotatsu.parsers.model.ContentType
import org.zeravrix.kotatsu.core.parser.mihon.MihonMangaSource

@AndroidEntryPoint
class SourcesCatalogActivity : BaseActivity<ActivitySourcesCatalogBinding>(),
	OnListItemClickListener<SourceCatalogItem.Source>,
	AppBarOwner,
	MenuItem.OnActionExpandListener,
	ChipsView.OnChipClickListener {

	override val appBar: AppBarLayout
		get() = viewBinding.appbar

	private val viewModel by viewModels<SourcesCatalogViewModel>()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivitySourcesCatalogBinding.inflate(layoutInflater))
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)
		val sourcesAdapter = SourcesCatalogAdapter(
			this,
			object : OnListItemClickListener<SourceCatalogItem.Extension> {
				override fun onItemClick(item: SourceCatalogItem.Extension, view: View) {
					val installedSource = org.zeravrix.kotatsu.core.parser.mihon.MihonSourceManager.sources.value
						.find { it.packageName == item.packageName }
					if (installedSource != null) {
						router.openList(installedSource, null, null)
						return
					}
					val loadedExt = org.zeravrix.kotatsu.extension.ExtensionManager.getInstance(applicationContext)
						.getLoadedExtensions().find { it.pkgName == item.packageName }
					val s = loadedExt?.sources?.firstOrNull()
					if (s != null) {
						val src = org.zeravrix.kotatsu.core.parser.mihon.MihonMangaSource(
							packageName = loadedExt.pkgName,
							sourceId = s.id,
							sourceName = s.name,
							language = s.lang,
							isNsfw = loadedExt.contentWarning == org.zeravrix.kotatsu.extension.model.ContentWarning.NSFW,
						)
						router.openList(src, null, null)
						return
					}
					if (item.isInstalled) {
						lifecycleScope.launch {
							val refreshed = org.zeravrix.kotatsu.core.parser.mihon.MihonSourceManager.refreshNow(applicationContext)
							val src = refreshed.find { it.packageName == item.packageName }
							if (src != null) {
								router.openList(src, null, null)
							} else {
								android.widget.Toast.makeText(this@SourcesCatalogActivity, "Loading source...", android.widget.Toast.LENGTH_SHORT).show()
							}
						}
						return
					}
					installExtension(item)
				}

				override fun onItemLongClick(item: SourceCatalogItem.Extension, view: View): Boolean {
					val installedSource = org.zeravrix.kotatsu.core.parser.mihon.MihonSourceManager.sources.value
						.find { it.packageName == item.packageName }
					if (installedSource != null) {
						if (item.isPinned) {
							viewModel.removeSource(installedSource)
						} else {
							viewModel.addSource(installedSource)
						}
						return true
					}
					if (item.isInstalled) {
						lifecycleScope.launch {
							val refreshed = org.zeravrix.kotatsu.core.parser.mihon.MihonSourceManager.refreshNow(applicationContext)
							val src = refreshed.find { it.packageName == item.packageName }
							if (src != null) {
								if (item.isPinned) {
									viewModel.removeSource(src)
								} else {
									viewModel.addSource(src)
								}
							}
						}
						return true
					}
					installExtension(item)
					return true
				}
			},
		)

		with(viewBinding.recyclerView) {
			setHasFixedSize(true)
			addItemDecoration(TypedListSpacingDecoration(context, false))
			adapter = sourcesAdapter
		}
		viewBinding.chipsFilter.onChipClickListener = this
		FadingAppbarMediator(viewBinding.appbar, viewBinding.toolbar).bind()
		viewModel.content.observe(this, sourcesAdapter)
		viewModel.onActionDone.observeEvent(
			this,
			ReversibleActionObserver(viewBinding.recyclerView),
		)
		combine(viewModel.appliedFilter, viewModel.hasNewSources, viewModel.contentTypes, ::Triple).observe(this) {
			updateFilers(it.first, it.second, it.third)
		}
		addMenuProvider(SourcesCatalogMenuProvider(this, viewModel, this))
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
		viewBinding.recyclerView.updatePadding(
			left = bars.left,
			right = bars.right,
			bottom = bars.bottom,
		)
		viewBinding.appbar.updatePadding(
			left = bars.left,
			right = bars.right,
			top = bars.top,
		)
		return WindowInsetsCompat.Builder(insets)
			.setInsets(WindowInsetsCompat.Type.systemBars(), Insets.NONE)
			.build()
	}

	override fun onChipClick(chip: Chip, data: Any?) {
		when (data) {
			is ContentType -> viewModel.setContentType(data, !chip.isChecked)
			is Boolean -> viewModel.setNewOnly(!chip.isChecked)
			else -> showLocalesMenu(chip)
		}
	}

	override fun onItemClick(item: SourceCatalogItem.Source, view: View) {
		val source = item.source
		if (source is MihonMangaSource) {
			val isInstalled = org.zeravrix.kotatsu.core.parser.mihon.MihonSourceManager.isPackageInstalled(source.packageName)
			if (!isInstalled) {
				showInstallSourcePrompt(source.sourceName, source.packageName)
				return
			}
		}
		router.openList(item.source, null, null)
	}

	private fun showInstallSourcePrompt(sourceName: String, packageName: String) {
		com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
			.setTitle(getString(R.string.install_extension_apk))
			.setMessage(getString(R.string.downloading_extension, sourceName) + "\n" + getString(R.string.source_extensions_summary))
			.setPositiveButton(R.string.install) { _, _ ->
				val catalogEntry = viewModel.findCatalogEntry(packageName)
				if (catalogEntry != null) {
					installExtension(
						SourceCatalogItem.Extension(
							sourceName = catalogEntry.sourceName,
							language = catalogEntry.language,
							extensionName = catalogEntry.extensionName,
							packageName = catalogEntry.packageName,
							versionName = catalogEntry.versionName,
							apkUrl = catalogEntry.apkUrl,
							iconUrl = catalogEntry.iconUrl,
							isInstalled = false,
							isInstalling = false,
							isPinned = false,
						)
					)
				} else {
					openExtensionManager()
				}
			}
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	override fun onItemLongClick(item: SourceCatalogItem.Source, view: View): Boolean {
		if (item.isPinned) {
			viewModel.removeSource(item.source)
		} else {
			viewModel.addSource(item.source)
		}
		return true
	}

		private fun openExtensionManager() {
			startActivity(Intent(this, ExtensionsActivity::class.java))
		}

		private fun installExtension(item: SourceCatalogItem.Extension) {
			com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
				.setTitle(getString(R.string.install_extension_apk))
				.setMessage(getString(R.string.downloading_extension, item.sourceName) + "\n" + getString(R.string.source_extensions_summary))
				.setPositiveButton(R.string.install) { _, _ ->
					android.widget.Toast.makeText(
						this,
						"${getString(R.string.download)}: ${item.sourceName}",
						android.widget.Toast.LENGTH_SHORT,
					).show()
					viewModel.installExtension(item) { success ->
						if (success) {
							android.widget.Toast.makeText(
								this,
								"${item.sourceName} installed successfully!",
								android.widget.Toast.LENGTH_SHORT,
							).show()
						} else {
							android.widget.Toast.makeText(
								this,
								"Installation failed. Please check network connection.",
								android.widget.Toast.LENGTH_SHORT,
							).show()
						}
					}
				}
				.setNegativeButton(android.R.string.cancel, null)
				.show()
		}

	override fun onMenuItemActionExpand(item: MenuItem): Boolean {
		val sq = (item.actionView as? SearchView)?.query?.trim()?.toString().orEmpty()
		viewModel.performSearch(sq)
		return true
	}

	override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
		viewModel.performSearch(null)
		return true
	}

	private fun updateFilers(
		appliedFilter: SourcesCatalogFilter,
		hasNewSources: Boolean,
		contentTypes: List<ContentType>,
	) {
		val chips = ArrayList<ChipModel>(contentTypes.size + 2)
		chips += ChipModel(
			title = appliedFilter.locale?.toLocale().getDisplayName(this),
			icon = R.drawable.ic_language,
			isDropdown = true,
		)
		if (hasNewSources) {
			chips += ChipModel(
				title = getString(R.string._new),
				icon = R.drawable.ic_updated,
				isChecked = appliedFilter.isNewOnly,
				data = true,
			)
		}
		contentTypes.mapTo(chips) { type ->
			ChipModel(
				title = getString(type.titleResId),
				isChecked = type in appliedFilter.types,
				data = type,
			)
		}
		viewBinding.chipsFilter.setChips(chips)
	}

	private fun showLocalesMenu(anchor: View) {
		val locales = viewModel.locales.mapTo(ArrayList(viewModel.locales.size)) {
			it to it?.toLocale()
		}
		locales.sortWith(compareBy(nullsFirst(LocaleComparator())) { it.second })
		val menu = PopupMenu(this, anchor)
		for ((i, lc) in locales.withIndex()) {
			menu.menu.add(Menu.NONE, Menu.NONE, i, lc.second.getDisplayName(this))
		}
		menu.setOnMenuItemClickListener {
			viewModel.setLocale(locales.getOrNull(it.order)?.first)
			true
		}
		menu.show()
	}
}
