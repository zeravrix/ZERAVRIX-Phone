package org.zeravrix.kotatsu.extension.model

sealed interface InstallStep {
    data object Idle : InstallStep
    data object Pending : InstallStep
    data object Downloading : InstallStep
    data object Installing : InstallStep
    data object Installed : InstallStep
    data object Error : InstallStep

    fun isFinished(): Boolean = this == Installed || this == Error || this == Idle

    fun isSuccess(): Boolean = this == Installed

    fun isError(): Boolean = this == Error

    @Deprecated("Use isFinished() or isSuccess() explicitly", ReplaceWith("isFinished()"))
    fun isCompleted(): Boolean = isFinished()
}
