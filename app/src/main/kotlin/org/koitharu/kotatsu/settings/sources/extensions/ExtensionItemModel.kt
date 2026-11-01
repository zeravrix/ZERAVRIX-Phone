package org.zeravrix.kotatsu.settings.sources.extensions

import org.zeravrix.kotatsu.extension.model.Extension
import org.zeravrix.kotatsu.extension.model.InstallStep
import org.zeravrix.kotatsu.list.ui.ListModelDiffCallback
import org.zeravrix.kotatsu.list.ui.model.ListModel

data class ExtensionItemModel(
    val extension: Extension,
    val installStep: InstallStep = InstallStep.Idle,
) : ListModel {

    override fun areItemsTheSame(other: ListModel): Boolean {
        return other is ExtensionItemModel && other.extension.pkgName == extension.pkgName
    }

    override fun getChangePayload(previousState: ListModel): Any? {
        if (previousState !is ExtensionItemModel) return null
        return if (previousState.installStep != installStep) {
            ListModelDiffCallback.PAYLOAD_PROGRESS_CHANGED
        } else {
            ListModelDiffCallback.PAYLOAD_ANYTHING_CHANGED
        }
    }
}
