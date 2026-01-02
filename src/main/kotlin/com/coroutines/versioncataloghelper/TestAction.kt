package com.coroutines.versioncataloghelper

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages

class TestAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        Messages.showMessageDialog(
            "Plugin is loaded!",
            "Test",
            Messages.getInformationIcon()
        )
    }
}