package org.zeravrix.kotatsu.settings.sources.extensions

import androidx.core.view.isVisible
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.zeravrix.kotatsu.R
import org.zeravrix.kotatsu.core.parser.mihon.MihonMangaSource
import org.zeravrix.kotatsu.core.parser.mihon.MihonSourceRuntime
import org.zeravrix.kotatsu.databinding.ItemExtensionBinding
import org.zeravrix.kotatsu.extension.model.ContentWarning
import org.zeravrix.kotatsu.extension.model.Extension
import org.zeravrix.kotatsu.extension.model.InstallStep
import org.zeravrix.kotatsu.list.ui.ListModelDiffCallback
import org.zeravrix.kotatsu.list.ui.model.ListModel

fun extensionItemAD(
    onItemClick: (Extension) -> Unit,
    onItemLongClick: (Extension, android.view.View) -> Boolean,
    onActionClick: (Extension) -> Unit,
) = adapterDelegateViewBinding<ExtensionItemModel, ListModel, ItemExtensionBinding>(
    { inflater, parent -> ItemExtensionBinding.inflate(inflater, parent, false) },
) {
    binding.root.setOnClickListener {
        onItemClick(item.extension)
    }

    binding.root.setOnLongClickListener { v ->
        onItemLongClick(item.extension, v)
    }

    binding.buttonAction.setOnClickListener {
        onActionClick(item.extension)
    }

    bind { payloads ->
        val ext = item.extension
        val step = item.installStep

        if (payloads.contains(ListModelDiffCallback.PAYLOAD_PROGRESS_CHANGED)) {
            binding.bindActionState(step, ext)
            return@bind
        }

        binding.textViewTitle.text = ext.name

        if (ext is Extension.NotLoaded) {
            binding.textViewDescription.text = when (val reason = ext.reason) {
                is Extension.NotLoaded.Reason.UnsupportedLibVersion -> {
                    context.getString(R.string.extension_incompatible_lib, ext.libVersion?.toString() ?: "unknown")
                }
                is Extension.NotLoaded.Reason.Untrusted -> {
                    context.getString(R.string.extension_untrusted_desc, reason.signatureHash.take(12) + "…")
                }
                is Extension.NotLoaded.Reason.Malformed -> {
                    context.getString(R.string.extension_malformed_desc)
                }
                is Extension.NotLoaded.Reason.Unsigned -> {
                    context.getString(R.string.extension_unsigned_desc)
                }
                is Extension.NotLoaded.Reason.Failed -> {
                    context.getString(R.string.extension_load_failed_desc, reason.message)
                }
                is Extension.NotLoaded.Reason.Filtered -> {
                    context.getString(R.string.extension_filtered_desc)
                }
            }
        } else {
            val countSources = when (ext) {
                is Extension.Loaded -> ext.sources.size
                is Extension.Available -> ext.sources.size
                else -> 0
            }
            val langStr = ext.lang?.uppercase() ?: "ALL"
            val sourcesDesc = if (countSources > 0) {
                context.getString(R.string.extension_sources, countSources)
            } else ""
            binding.textViewDescription.text = listOfNotNull(
                langStr,
                "v${ext.versionName}",
                sourcesDesc.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
        }

        // Badges: NotLoaded (Untrusted, Incompatible, Malformed, etc.), Update, or NSFW
        when {
            ext is Extension.NotLoaded -> {
                binding.textViewBadge.isVisible = true
                binding.textViewBadge.text = when (ext.reason) {
                    is Extension.NotLoaded.Reason.Untrusted -> context.getString(R.string.untrusted)
                    is Extension.NotLoaded.Reason.UnsupportedLibVersion -> context.getString(R.string.incompatible)
                    is Extension.NotLoaded.Reason.Malformed -> context.getString(R.string.malformed)
                    is Extension.NotLoaded.Reason.Unsigned -> context.getString(R.string.unsigned)
                    is Extension.NotLoaded.Reason.Failed -> context.getString(R.string.error)
                    is Extension.NotLoaded.Reason.Filtered -> context.getString(R.string.filter)
                }
            }
            ext is Extension.Installed && ext.hasUpdate -> {
                binding.textViewBadge.isVisible = true
                binding.textViewBadge.text = context.getString(R.string.update)
            }
            ext.contentWarning == ContentWarning.NSFW -> {
                binding.textViewBadge.isVisible = true
                binding.textViewBadge.text = "18+"
            }
            else -> {
                binding.textViewBadge.isVisible = false
            }
        }

        // Icon loading through FaviconFetcher / Coil
        if (ext is Extension.Installed) {
            val src = (ext as? Extension.Loaded)?.sources?.firstOrNull()?.let {
                MihonMangaSource(ext.pkgName, it.id, it.name, it.lang)
            } ?: MihonMangaSource(ext.pkgName, 0L, ext.name, ext.lang.orEmpty())
            binding.imageViewIcon.setImageAsync(src)
        } else if (ext is Extension.Available && ext.iconUrl.isNotBlank()) {
            binding.imageViewIcon.setImageAsync(ext.iconUrl)
        } else {
            binding.imageViewIcon.setImageAsync(R.drawable.zeravrix_chibi)
        }

        binding.bindActionState(step, ext)
    }
}

private fun ItemExtensionBinding.bindActionState(step: InstallStep, ext: Extension) {
    if (step == InstallStep.Downloading || step == InstallStep.Installing || step == InstallStep.Pending) {
        buttonAction.isVisible = false
        progressAction.isVisible = true
    } else {
        progressAction.isVisible = false
        when {
            ext is Extension.NotLoaded && ext.reason is Extension.NotLoaded.Reason.Untrusted -> {
                buttonAction.isVisible = true
                buttonAction.text = root.context.getString(R.string.trust)
            }
            ext is Extension.Available -> {
                buttonAction.isVisible = true
                buttonAction.text = root.context.getString(R.string.install)
            }
            ext is Extension.Installed && ext.hasUpdate -> {
                buttonAction.isVisible = true
                buttonAction.text = root.context.getString(R.string.update)
            }
            else -> {
                buttonAction.isVisible = false
            }
        }
    }
}
