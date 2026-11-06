package com.timezy.widget.ui.screens.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.timezy.widget.CountdownApplication
import com.timezy.widget.data.model.SortOption
import com.timezy.widget.data.preferences.AppPreferences
import com.timezy.widget.data.repository.EventRepository
import com.timezy.widget.widget.WidgetUpdateHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val themeMode: String = "SYSTEM", // SYSTEM, DARK, LIGHT
    val notificationsEnabled: Boolean = true,
    val showSeconds: Boolean = true,
    val defaultSort: SortOption = SortOption.TARGET_DATE_ASC,
    val showClearDataDialog: Boolean = false,
    val dataCleared: Boolean = false
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val preferences: AppPreferences = (application as CountdownApplication).preferences
    private val repository: EventRepository = (application as CountdownApplication).repository

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            preferences.themeModeFlow.collect { mode ->
                _uiState.update { it.copy(themeMode = mode) }
            }
        }
        viewModelScope.launch {
            preferences.notificationsEnabledFlow.collect { enabled ->
                _uiState.update { it.copy(notificationsEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            preferences.showSecondsFlow.collect { show ->
                _uiState.update { it.copy(showSeconds = show) }
            }
        }
        viewModelScope.launch {
            preferences.defaultSortFlow.collect { sortName ->
                _uiState.update { it.copy(defaultSort = SortOption.fromName(sortName)) }
            }
        }
    }

    fun setThemeMode(mode: String) {
        _uiState.update { it.copy(themeMode = mode) }
        viewModelScope.launch {
            preferences.setThemeMode(mode)
        }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setNotificationsEnabled(enabled)
        }
    }

    fun setShowSeconds(show: Boolean) {
        viewModelScope.launch {
            preferences.setShowSeconds(show)
        }
    }

    fun setDefaultSort(sort: SortOption) {
        viewModelScope.launch {
            preferences.setDefaultSort(sort.name)
        }
    }

    fun showClearDataDialog() {
        _uiState.update { it.copy(showClearDataDialog = true) }
    }

    fun dismissClearDataDialog() {
        _uiState.update { it.copy(showClearDataDialog = false) }
    }

    fun clearAllData() {
        viewModelScope.launch {
            repository.clearAllEvents()
            WidgetUpdateHelper.handleAllEventsClearedSync(getApplication())
            _uiState.update {
                it.copy(showClearDataDialog = false, dataCleared = true)
            }
        }
    }

    fun onDataClearedHandled() {
        _uiState.update { it.copy(dataCleared = false) }
    }
}
