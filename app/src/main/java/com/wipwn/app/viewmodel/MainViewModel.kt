package com.wipwn.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.wipwn.app.WipwnApp
import com.wipwn.app.repository.WpsRepository
import kotlinx.coroutines.flow.StateFlow

/**
 * Top-level VM that exposes the root/library init state. Lightweight — all
 * heavy lifting lives in [WpsRepository].
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo: WpsRepository = (app as WipwnApp).repository

    val initState: StateFlow<WpsRepository.InitState> = repo.initState
    val historyCountSource = repo.history

    init {
        if (!repo.initState.value.isLibraryReady) {
            repo.initialize()
        }
    }

    fun retry() {
        repo.initialize()
    }
}
