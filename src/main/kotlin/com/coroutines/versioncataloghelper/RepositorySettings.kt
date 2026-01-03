package com.coroutines.versioncataloghelper

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(
    name = "VersionCatalogHelperSettings",
    storages = [Storage("versionCatalogHelper.xml")]
)
class RepositorySettings : PersistentStateComponent<RepositorySettings.State> {

    data class State(
        var enabledRepositories: MutableList<String> = mutableListOf(
            "Maven Central",
            "Google Maven"
        )
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    fun isRepositoryEnabled(repo: String): Boolean {
        return myState.enabledRepositories.contains(repo)
    }

    fun setRepositoryEnabled(repo: String, enabled: Boolean) {
        if (enabled && !myState.enabledRepositories.contains(repo)) {
            myState.enabledRepositories.add(repo)
        } else if (!enabled) {
            myState.enabledRepositories.remove(repo)
        }
    }

    fun getEnabledRepositories(): List<String> {
        return myState.enabledRepositories.toList()
    }

    companion object {
        fun getInstance(project: Project): RepositorySettings {
            return project.service()
        }

        val AVAILABLE_REPOSITORIES = listOf(
            "Maven Central",
            "Google Maven",
            "JitPack",
            "JCenter (deprecated)",
            "Gradle Plugin Portal"
        )
    }
}