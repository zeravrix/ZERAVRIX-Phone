package org.zeravrix.kotatsu.settings.sources.extensions

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.core.graphics.Insets
import androidx.core.view.MenuProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import coil3.ImageLoader
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.zeravrix.kotatsu.R
import org.zeravrix.kotatsu.core.nav.router
import org.zeravrix.kotatsu.core.parser.mihon.MihonMangaSource
import org.zeravrix.kotatsu.core.parser.mihon.MihonSourceRuntime
import org.zeravrix.kotatsu.core.ui.BaseActivity
import org.zeravrix.kotatsu.core.ui.util.FadingAppbarMediator
import org.zeravrix.kotatsu.core.ui.widgets.ChipsView
import org.zeravrix.kotatsu.core.util.ext.observe
import org.zeravrix.kotatsu.databinding.ActivityExtensionsBinding
import org.zeravrix.kotatsu.extension.model.ContentWarning
import org.zeravrix.kotatsu.extension.model.Extension
import org.zeravrix.kotatsu.list.ui.adapter.TypedListSpacingDecoration
import javax.inject.Inject

@AndroidEntryPoint
class ExtensionsActivity : BaseActivity<ActivityExtensionsBinding>(),
    ChipsView.OnChipClickListener {

    @Inject
    lateinit var coil: ImageLoader

    private val viewModel by viewModels<ExtensionsViewModel>()

    private val openFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            viewModel.installFromUri(uri)
            Toast.makeText(this, R.string.extension_install_started, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ActivityExtensionsBinding.inflate(layoutInflater))
        setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)
        setTitle(R.string.source_extensions)

        val adapter = ExtensionsAdapter(
            onItemClick = { ext ->
                if (ext is Extension.Installed) {
                    val src = resolveSourceForExtension(ext)
                    if (src != null) {
                        router.openList(src, null, null)
                    } else {
                        lifecycleScope.launch {
                            viewModel.extensionManager.loadExtensions()
                            val resolved = resolveSourceForExtension(ext) ?: MihonMangaSource(
                                packageName = ext.pkgName,
                                sourceId = (ext as? Extension.Loaded)?.sources?.firstOrNull()?.id ?: 0L,
                                sourceName = (ext as? Extension.Loaded)?.sources?.firstOrNull()?.name ?: ext.name,
                                language = (ext as? Extension.Loaded)?.sources?.firstOrNull()?.lang ?: ext.lang.orEmpty(),
                                isNsfw = ext.contentWarning == ContentWarning.NSFW,
                            )
                            router.openList(resolved, null, null)
                        }
                    }
                } else {
                    ExtensionDetailsSheet.show(supportFragmentManager, ext.pkgName)
                }
            },
            onItemLongClick = { ext, view ->
                if (ext is Extension.Installed) {
                    showExtensionContextMenu(ext, view)
                    true
                } else {
                    false
                }
            },
            onActionClick = { ext ->
                when {
                    ext is Extension.Available -> viewModel.install(ext)
                    ext is Extension.Installed && ext.hasUpdate -> viewModel.update(ext)
                    ext is Extension.NotLoaded -> viewModel.trust(ext)
                }
            },
        )

        with(viewBinding.recyclerView) {
            setHasFixedSize(true)
            addItemDecoration(TypedListSpacingDecoration(context, false))
            this.adapter = adapter
        }

        viewBinding.chipsFilter.onChipClickListener = this
        FadingAppbarMediator(viewBinding.appbar, viewBinding.toolbar).bind()

        viewModel.items.observe(this) { items ->
            adapter.setItems(items)
        }

        combine(
            viewModel.filterTab,
            viewModel.installedCount,
            viewModel.updatesCount,
            viewModel.availableCount,
            viewModel.selectedLanguage,
        ) { tab, installed, updates, available, lang ->
            FilterChipData(tab, installed, updates, available, lang)
        }.observe(this) { data ->
            updateFilterChips(data)
        }

        addMenuProvider(ExtensionsMenuProvider())
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

    private data class FilterChipData(
        val tab: FilterTab,
        val installedCount: Int,
        val updatesCount: Int,
        val availableCount: Int,
        val selectedLanguage: String?,
    )

    private fun updateFilterChips(data: FilterChipData) {
        val chips = mutableListOf<ChipsView.ChipModel>()

        chips.add(
            ChipsView.ChipModel(
                title = getString(R.string.all),
                isChecked = data.tab == FilterTab.ALL,
                data = FilterTab.ALL,
            ),
        )

        chips.add(
            ChipsView.ChipModel(
                title = getString(R.string.installed),
                counter = data.installedCount,
                isChecked = data.tab == FilterTab.INSTALLED,
                data = FilterTab.INSTALLED,
            ),
        )

        if (data.updatesCount > 0) {
            chips.add(
                ChipsView.ChipModel(
                    title = getString(R.string.updates),
                    counter = data.updatesCount,
                    isChecked = data.tab == FilterTab.UPDATES,
                    data = FilterTab.UPDATES,
                ),
            )
        }

        chips.add(
            ChipsView.ChipModel(
                title = getString(R.string.available_extensions),
                counter = data.availableCount,
                isChecked = data.tab == FilterTab.AVAILABLE,
                data = FilterTab.AVAILABLE,
            ),
        )

        chips.add(
            ChipsView.ChipModel(
                title = data.selectedLanguage?.uppercase() ?: getString(R.string.all_languages),
                isDropdown = true,
                data = "LANG_DROPDOWN",
            ),
        )

        viewBinding.chipsFilter.setChips(chips)
    }

    override fun onChipClick(chip: Chip, data: Any?) {
        when (data) {
            is FilterTab -> viewModel.setFilterTab(data)
            "LANG_DROPDOWN" -> showLanguageMenu(chip)
        }
    }

    private fun showLanguageMenu(anchor: View) {
        val langs = viewModel.availableLanguages.value
        val popup = PopupMenu(this, anchor)
        popup.menu.add(Menu.NONE, 0, 0, getString(R.string.all_languages))
        for ((idx, lang) in langs.withIndex()) {
            popup.menu.add(Menu.NONE, idx + 1, idx + 1, lang.uppercase())
        }
        popup.setOnMenuItemClickListener { menuItem ->
            if (menuItem.itemId == 0) {
                viewModel.setSelectedLanguage(null)
            } else {
                val selected = langs.getOrNull(menuItem.itemId - 1)
                viewModel.setSelectedLanguage(selected)
            }
            true
        }
        popup.show()
    }

    private fun showAddRepositoryDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.extension_repository_url_hint)
            setSingleLine(true)
            setPadding(48, 32, 48, 32)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_repository)
            .setView(input)
            .setPositiveButton(R.string.add) { _, _ ->
                val url = input.text.toString().trim()
                if (url.startsWith("https://")) {
                    val added = viewModel.addRepository(url)
                    if (!added) {
                        Toast.makeText(this, "Repository already added or invalid", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, R.string.extension_repository_https_required, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showManageRepositoriesDialog() {
        val repos = viewModel.getRepositories().toList().sorted()
        if (repos.isEmpty()) {
            Toast.makeText(this, R.string.no_extension_repositories, Toast.LENGTH_SHORT).show()
            return
        }

        val items = repos.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.manage_repositories)
            .setItems(items) { _, which ->
                val selected = items[which]
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.delete)
                    .setMessage(selected)
                    .setPositiveButton(R.string.delete) { _, _ ->
                        val removed = viewModel.removeRepository(selected)
                        if (removed) {
                            Toast.makeText(this, "Repository removed", Toast.LENGTH_SHORT).show()
                            if (viewModel.getRepositories().isNotEmpty()) {
                                showManageRepositoriesDialog()
                            }
                        } else {
                            Toast.makeText(this, "Failed to remove repository", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private inner class ExtensionsMenuProvider : MenuProvider {
        override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
            menuInflater.inflate(R.menu.opt_extensions, menu)
            val searchItem = menu.findItem(R.id.action_search)
            val searchView = searchItem?.actionView as? SearchView
            searchView?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean {
                    viewModel.setSearchQuery(query.orEmpty())
                    return true
                }

                override fun onQueryTextChange(newText: String?): Boolean {
                    viewModel.setSearchQuery(newText.orEmpty())
                    return true
                }
            })
        }

        override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
            return when (menuItem.itemId) {
                R.id.action_refresh -> {
                    viewModel.refreshCatalog()
                    true
                }
                R.id.action_add_repo -> {
                    showAddRepositoryDialog()
                    true
                }
                R.id.action_manage_repos -> {
                    showManageRepositoriesDialog()
                    true
                }
                R.id.action_install_file -> {
                    openFileLauncher.launch("*/*")
                    true
                }
                else -> false
            }
        }
    }

    private fun resolveSourceForExtension(ext: Extension): org.koitharu.kotatsu.parsers.model.MangaSource? {
        if (ext is Extension.Loaded) {
            val s = ext.sources.firstOrNull()
            if (s != null) {
                return MihonMangaSource(
                    packageName = ext.pkgName,
                    sourceId = s.id,
                    sourceName = s.name,
                    language = s.lang,
                    isNsfw = ext.contentWarning == ContentWarning.NSFW,
                )
            }
        }
        val loaded = viewModel.extensionManager.getLoadedExtensions().find { it.pkgName == ext.pkgName }
        val s = loaded?.sources?.firstOrNull()
        if (loaded != null && s != null) {
            return MihonMangaSource(
                packageName = ext.pkgName,
                sourceId = s.id,
                sourceName = s.name,
                language = s.lang,
                isNsfw = loaded.contentWarning == ContentWarning.NSFW,
            )
        }
        return null
    }

    private fun showExtensionContextMenu(ext: Extension.Installed, anchor: View) {
        lifecycleScope.launch {
            val isPinned = viewModel.isExtensionPinned(ext.pkgName)
            val popup = PopupMenu(this@ExtensionsActivity, anchor)

            popup.menu.add(Menu.NONE, 1, 1, R.string.explore)
            popup.menu.add(Menu.NONE, 2, 2, if (isPinned) R.string.unpin else R.string.pin)
            popup.menu.add(Menu.NONE, 3, 3, R.string.uninstall)

            popup.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    1 -> {
                        val src = resolveSourceForExtension(ext)
                        if (src != null) {
                            router.openList(src, null, null)
                        } else {
                            lifecycleScope.launch {
                                viewModel.extensionManager.loadExtensions()
                                val resolved = resolveSourceForExtension(ext) ?: MihonMangaSource(
                                    packageName = ext.pkgName,
                                    sourceId = (ext as? Extension.Loaded)?.sources?.firstOrNull()?.id ?: 0L,
                                    sourceName = (ext as? Extension.Loaded)?.sources?.firstOrNull()?.name ?: ext.name,
                                    language = (ext as? Extension.Loaded)?.sources?.firstOrNull()?.lang ?: ext.lang.orEmpty(),
                                    isNsfw = ext.contentWarning == ContentWarning.NSFW,
                                )
                                router.openList(resolved, null, null)
                            }
                        }
                        true
                    }
                    2 -> {
                        val newPinned = !isPinned
                        viewModel.toggleExtensionPin(ext.pkgName, newPinned)
                        val msgRes = if (newPinned) R.string.source_pinned else R.string.source_unpinned
                        Toast.makeText(this@ExtensionsActivity, msgRes, Toast.LENGTH_SHORT).show()
                        true
                    }
                    3 -> {
                        MaterialAlertDialogBuilder(this@ExtensionsActivity)
                            .setTitle(R.string.uninstall)
                            .setMessage(getString(R.string.uninstall_extension_confirm, ext.name))
                            .setPositiveButton(R.string.uninstall) { _, _ ->
                                viewModel.uninstall(ext.pkgName)
                                Toast.makeText(this@ExtensionsActivity, R.string.extension_uninstalled, Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }
    }

    companion object {
        fun newIntent(context: Context): Intent = Intent(context, ExtensionsActivity::class.java)
    }
}
