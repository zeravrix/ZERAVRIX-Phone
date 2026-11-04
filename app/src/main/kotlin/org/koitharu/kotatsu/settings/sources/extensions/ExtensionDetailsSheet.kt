package org.zeravrix.kotatsu.settings.sources.extensions

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import org.zeravrix.kotatsu.R
import org.zeravrix.kotatsu.core.nav.router
import org.zeravrix.kotatsu.core.parser.mihon.MihonMangaSource
import org.zeravrix.kotatsu.core.parser.mihon.MihonSourceRuntime
import org.zeravrix.kotatsu.core.ui.sheet.BaseAdaptiveSheet
import org.zeravrix.kotatsu.databinding.SheetExtensionDetailsBinding
import org.zeravrix.kotatsu.extension.ExtensionManager
import org.zeravrix.kotatsu.extension.model.Extension

@AndroidEntryPoint
class ExtensionDetailsSheet : BaseAdaptiveSheet<SheetExtensionDetailsBinding>() {

    private val pkgName: String by lazy {
        requireArguments().getString(ARG_PACKAGE_NAME).orEmpty()
    }

    override fun onCreateViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
    ): SheetExtensionDetailsBinding {
        return SheetExtensionDetailsBinding.inflate(inflater, container, false)
    }

    override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
        return insets
    }

    override fun onViewBindingCreated(
        binding: SheetExtensionDetailsBinding,
        savedInstanceState: Bundle?,
    ) {
        super.onViewBindingCreated(binding, savedInstanceState)
        val extActivity = activity as? ExtensionsActivity
        val extensionManager = extActivity?.viewModel?.extensionManager
            ?: ExtensionManager.getInstance(requireContext())

        val extension = extensionManager.getLoadedExtensions().find { it.pkgName == pkgName }
            ?: extensionManager.getNotLoadedExtensions().find { it.pkgName == pkgName }
            ?: extensionManager.availableExtensionsFlow.value.find { it.pkgName == pkgName }

        if (extension == null) {
            dismiss()
            return
        }

        binding.textViewName.text = extension.name
        binding.textViewVersion.text = "v${extension.versionName} · ${extension.pkgName}"

        // Icon
        if (extension is Extension.Installed) {
            val src = (extension as? Extension.Loaded)?.sources?.firstOrNull()?.let {
                MihonMangaSource(extension.pkgName, it.id, it.name, it.lang)
            } ?: MihonMangaSource(extension.pkgName, 0L, extension.name, extension.lang.orEmpty())
            binding.imageViewIcon.setImageAsync(src)
        } else if (extension is Extension.Available && extension.iconUrl.isNotBlank()) {
            binding.imageViewIcon.setImageAsync(extension.iconUrl)
        } else {
            binding.imageViewIcon.setImageAsync(R.drawable.zeravrix_chibi)
        }

        // Trust status and NotLoaded reasons
        when (val reason = (extension as? Extension.NotLoaded)?.reason) {
            is Extension.NotLoaded.Reason.Untrusted -> {
                binding.textViewStatus.isVisible = true
                binding.textViewStatus.text = getString(R.string.untrusted_extension_warning) + "\n\nHash: " + reason.signatureHash
                binding.buttonTrust.isVisible = true
                binding.buttonTrust.setOnClickListener {
                    if (extActivity != null && extension is Extension.NotLoaded) {
                        extActivity.viewModel.trust(extension)
                    } else {
                        extensionManager.trustExtension.trust(extension.pkgName, extension.versionCode, reason.signatureHash)
                    }
                    dismiss()
                }
            }
            is Extension.NotLoaded.Reason.UnsupportedLibVersion -> {
                binding.textViewStatus.isVisible = true
                binding.textViewStatus.text = getString(R.string.extension_incompatible_lib, extension.libVersion?.toString() ?: "unknown")
                binding.buttonTrust.isVisible = false
            }
            is Extension.NotLoaded.Reason.Malformed -> {
                binding.textViewStatus.isVisible = true
                binding.textViewStatus.text = getString(R.string.extension_malformed_desc)
                binding.buttonTrust.isVisible = false
            }
            is Extension.NotLoaded.Reason.Unsigned -> {
                binding.textViewStatus.isVisible = true
                binding.textViewStatus.text = getString(R.string.extension_unsigned_desc)
                binding.buttonTrust.isVisible = false
            }
            is Extension.NotLoaded.Reason.Failed -> {
                binding.textViewStatus.isVisible = true
                binding.textViewStatus.text = getString(R.string.extension_load_failed_desc, reason.message)
                binding.buttonTrust.isVisible = false
            }
            is Extension.NotLoaded.Reason.Filtered -> {
                binding.textViewStatus.isVisible = true
                binding.textViewStatus.text = getString(R.string.extension_filtered_desc)
                binding.buttonTrust.isVisible = false
            }
            else -> {
                binding.textViewStatus.isVisible = false
                binding.buttonTrust.isVisible = false
            }
        }

        // Sources list
        binding.layoutSources.removeAllViews()
        val sources = when (extension) {
            is Extension.Loaded -> extension.sources.map { it.name to "" }
            is Extension.Available -> extension.sources.map { it.name to it.baseUrl }
            else -> emptyList()
        }

        if (sources.isNotEmpty()) {
            val title = TextView(requireContext()).apply {
                setText(R.string.remote_sources)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
                setPadding(0, 16, 0, 8)
            }
            binding.layoutSources.addView(title)

            for ((name, url) in sources) {
                val row = TextView(requireContext()).apply {
                    text = if (url.isNotBlank()) "$name ($url)" else name
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                    setPadding(16, 12, 16, 12)
                    val typedValue = android.util.TypedValue()
                    context.theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)
                    setBackgroundResource(typedValue.resourceId)
                    val realSource = (extension as? Extension.Loaded)?.sources?.find { it.name == name }
                        ?: org.zeravrix.kotatsu.extension.ExtensionManager.getInstance(requireContext())
                            .getLoadedExtensions().find { it.pkgName == extension.pkgName }?.sources?.find { it.name == name }
                    val mihonSource = if (realSource != null) {
                        MihonMangaSource(
                            packageName = extension.pkgName,
                            sourceId = realSource.id,
                            sourceName = realSource.name,
                            language = realSource.lang,
                        )
                    } else {
                        MihonMangaSource.fromKey("mihon:${extension.pkgName}:0")
                    }
                    if (mihonSource != null) {
                        setOnClickListener {
                            dismiss()
                            router.openList(mihonSource, null, null)
                        }
                    }
                }
                binding.layoutSources.addView(row)
            }
        }

        // Uninstall
        if (extension is Extension.Installed) {
            binding.buttonUninstall.isVisible = true
            binding.buttonUninstall.setOnClickListener {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.uninstall)
                    .setMessage(getString(R.string.uninstall_extension_confirm, extension.name))
                    .setPositiveButton(R.string.uninstall) { _, _ ->
                        val extActivity = activity as? ExtensionsActivity
                        if (extActivity != null) {
                            extActivity.viewModel.uninstall(extension.pkgName)
                        } else {
                            extensionManager.uninstallExtension(extension.pkgName)
                        }
                        Toast.makeText(requireContext(), R.string.extension_uninstalled, Toast.LENGTH_SHORT).show()
                        dismiss()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        } else {
            binding.buttonUninstall.isVisible = false
        }
    }

    companion object {
        private const val TAG = "ExtensionDetailsSheet"
        private const val ARG_PACKAGE_NAME = "pkg_name"

        fun show(fm: FragmentManager, pkgName: String) {
            ExtensionDetailsSheet().apply {
                arguments = bundleOf(ARG_PACKAGE_NAME to pkgName)
            }.show(fm, TAG)
        }
    }
}
