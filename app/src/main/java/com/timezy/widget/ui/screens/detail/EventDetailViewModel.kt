package com.timezy.widget.ui.screens.detail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.timezy.widget.CountdownApplication
import com.timezy.widget.data.model.CountdownEvent
import com.timezy.widget.data.repository.EventRepository
import com.timezy.widget.notifications.ReminderScheduler
import com.timezy.widget.widget.WidgetUpdateHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class EventDetailViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: EventRepository = (application as CountdownApplication).repository
    private val preferences = (application as CountdownApplication).preferences

    val showSeconds: StateFlow<Boolean> = preferences.showSecondsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = true
    )

    private val _event = MutableStateFlow<CountdownEvent?>(null)
    val event: StateFlow<CountdownEvent?> = _event.asStateFlow()

    private val _nowMillis = MutableStateFlow(System.currentTimeMillis())
    val nowMillis: StateFlow<Long> = _nowMillis.asStateFlow()

    private val _isDeleted = MutableStateFlow(false)
    val isDeleted: StateFlow<Boolean> = _isDeleted.asStateFlow()

    init {
        viewModelScope.launch {
            while (true) {
                delay(1000)
                _nowMillis.value = System.currentTimeMillis()
            }
        }
    }

    fun loadEvent(eventId: Long) {
        viewModelScope.launch {
            repository.getEventByIdFlow(eventId).collect {
                _event.value = it
            }
        }
    }

    fun toggleFavorite() {
        val current = _event.value ?: return
        viewModelScope.launch {
            repository.toggleFavorite(current.id, current.isFavorite)
            WidgetUpdateHelper.updateAllWidgets(getApplication())
        }
    }

    fun deleteEvent() {
        val current = _event.value ?: return
        viewModelScope.launch {
            repository.deleteEventById(current.id)
            ReminderScheduler.cancelReminder(getApplication(), current.id)
            WidgetUpdateHelper.handleEventDeletedSync(getApplication(), current.id)
            _isDeleted.value = true
        }
    }
}
