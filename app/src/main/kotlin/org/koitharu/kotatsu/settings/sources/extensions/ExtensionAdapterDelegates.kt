package org.zeravrix.kotatsu.settings.sources.extensions

import androidx.core.view.isVisible
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.zeravrix.kotatsu.R
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

        // Badges: NSFW or Untrusted or Update
        when {
            ext is Extension.NotLoaded && ext.reason is Extension.NotLoaded.Reason.Untrusted -> {
                binding.textViewBadge.isVisible = true
                binding.textViewBadge.text = context.getString(R.string.untrusted)
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

        // Icon loading
        if (ext is Extension.Installed) {
            val localIcon = MihonSourceRuntime.getIcon(context, ext.pkgName)
            if (localIcon != null) {
                binding.imageViewIcon.setImageDrawable(localIcon)
            } else {
                binding.imageViewIcon.setImageAsync(R.drawable.zeravrix_chibi)
            }
        } else if (ext is Extension.Available) {
            if (ext.iconUrl.isNotBlank()) {
                binding.imageViewIcon.setImageAsync(ext.iconUrl)
            } else {
                binding.imageViewIcon.setImageAsync(R.drawable.zeravrix_chibi)
            }
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
