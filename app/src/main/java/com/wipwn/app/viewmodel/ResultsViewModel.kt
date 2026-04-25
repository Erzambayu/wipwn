package com.wipwn.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wipwn.app.WipwnApp
import com.wipwn.app.data.AttackResult
import com.wipwn.app.repository.WpsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ResultsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo: WpsRepository = (app as WipwnApp).repository

    val results: StateFlow<List<AttackResult>> = repo.history.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    fun clearHistory() {
        viewModelScope.launch { repo.clearHistory() }
    }
}
