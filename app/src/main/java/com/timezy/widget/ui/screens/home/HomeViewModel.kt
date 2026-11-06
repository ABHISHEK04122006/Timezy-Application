package com.timezy.widget.ui.screens.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.timezy.widget.CountdownApplication
import com.timezy.widget.data.model.CountdownEvent
import com.timezy.widget.data.model.FilterOption
import com.timezy.widget.data.model.SortOption
import com.timezy.widget.data.preferences.AppPreferences
import com.timezy.widget.data.repository.EventRepository
import com.timezy.widget.notifications.ReminderScheduler
import com.timezy.widget.widget.WidgetUpdateHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val searchQuery: String = "",
    val selectedFilter: FilterOption = FilterOption.ALL,
    val sortOption: SortOption = SortOption.TARGET_DATE_ASC,
    val featuredEvent: CountdownEvent? = null,
    val upcomingEvents: List<CountdownEvent> = emptyList(),
    val counts: Map<FilterOption, Int> = emptyMap(),
    val eventToDelete: CountdownEvent? = null,
    val showSeconds: Boolean = true,
    val isLoading: Boolean = false
)

private data class FilterParams(
    val query: String = "",
    val filter: FilterOption = FilterOption.ALL,
    val sort: SortOption = SortOption.TARGET_DATE_ASC,
    val featuredIndex: Int = 0,
    val eventToDelete: CountdownEvent? = null
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: EventRepository = (application as CountdownApplication).repository
    private val preferences: AppPreferences = (application as CountdownApplication).preferences

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _selectedFilter = MutableStateFlow(FilterOption.ALL)
    val selectedFilter = _selectedFilter.asStateFlow()

    private val _sortOption = MutableStateFlow(SortOption.TARGET_DATE_ASC)
    val sortOption = _sortOption.asStateFlow()

    private val _featuredIndex = MutableStateFlow(0)
    val featuredIndex = _featuredIndex.asStateFlow()

    private val _eventToDelete = MutableStateFlow<CountdownEvent?>(null)
    val eventToDelete = _eventToDelete.asStateFlow()

    private val _nowMillis = MutableStateFlow(System.currentTimeMillis())
    val nowMillis = _nowMillis.asStateFlow()

    init {
        // Live second ticker for live countdown updates
        viewModelScope.launch {
            while (true) {
                delay(1000)
                _nowMillis.value = System.currentTimeMillis()
            }
        }
        // Observe default sort from preferences
        viewModelScope.launch {
            preferences.defaultSortFlow.collect { sortName ->
                val sort = SortOption.fromName(sortName)
                if (_sortOption.value != sort) {
                    _sortOption.value = sort
                    _featuredIndex.value = 0
                }
            }
        }
    }

    private val filterParams = combine(
        _searchQuery,
        _selectedFilter,
        _sortOption,
        _featuredIndex,
        _eventToDelete
    ) { query, filter, sort, featuredIdx, eventToDelete ->
        FilterParams(query, filter, sort, featuredIdx, eventToDelete)
    }

    val uiState: StateFlow<HomeUiState> = combine(
        repository.getAllEventsFlow(),
        filterParams,
        _nowMillis,
        preferences.showSecondsFlow
    ) { allEvents: List<CountdownEvent>, params: FilterParams, now: Long, showSecs: Boolean ->
        val query = params.query
        val filter = params.filter
        val sort = params.sort
        val featuredIdx = params.featuredIndex
        val eventToDelete = params.eventToDelete

        // Calculate category counts
        val upcomingList = allEvents.filter { !it.isPast(now) }
        val todayList = allEvents.filter { it.isToday(now) }
        val pastList = allEvents.filter { it.isPast(now) && !it.isToday(now) }
        val favList = allEvents.filter { it.isFavorite }

        val counts = mapOf(
            FilterOption.ALL to allEvents.size,
            FilterOption.UPCOMING to upcomingList.size,
            FilterOption.TODAY to todayList.size,
            FilterOption.PAST to pastList.size,
            FilterOption.FAVORITES to favList.size
        )

        // Filter events by selected category tab
        var filtered = when (filter) {
            FilterOption.ALL -> allEvents
            FilterOption.UPCOMING -> upcomingList
            FilterOption.TODAY -> todayList
            FilterOption.PAST -> pastList
            FilterOption.FAVORITES -> favList
        }

        // Apply search query
        if (query.isNotBlank()) {
            filtered = filtered.filter {
                it.title.contains(query, ignoreCase = true) ||
                        it.description.contains(query, ignoreCase = true) ||
                        it.category.displayName.contains(query, ignoreCase = true)
            }
        }

        // Apply sorting
        val sorted = when (sort) {
            SortOption.TARGET_DATE_ASC -> {
                val upcoming = filtered.filter { !it.isPast(now) }.sortedBy { it.getEffectiveTargetEpochMillis(now) }
                val past = filtered.filter { it.isPast(now) }.sortedByDescending { it.getEffectiveTargetEpochMillis(now) }
                upcoming + past
            }
            SortOption.TARGET_DATE_DESC -> {
                val upcoming = filtered.filter { !it.isPast(now) }.sortedByDescending { it.getEffectiveTargetEpochMillis(now) }
                val past = filtered.filter { it.isPast(now) }.sortedBy { it.getEffectiveTargetEpochMillis(now) }
                upcoming + past
            }
            SortOption.CREATED_AT_DESC -> {
                filtered.sortedWith(
                    compareByDescending<CountdownEvent> {
                        if (it.createdAtEpochMillis > 0L) it.createdAtEpochMillis else it.id
                    }.thenByDescending { it.id }
                )
            }
            SortOption.TITLE_ASC -> {
                filtered.sortedBy { it.title.lowercase() }
            }
        }

        // Select featured event directly from the sorted events
        val count = sorted.size
        val featured = if (query.isBlank() && filter == FilterOption.ALL && count > 0) {
            val safeIndex = ((featuredIdx % count) + count) % count
            sorted[safeIndex]
        } else {
            null
        }

        // Exclude featured event from list if featured is visible in ALL to avoid redundancy
        val remainingList = if (featured != null && query.isBlank() && filter == FilterOption.ALL) {
            sorted.filter { it.id != featured.id }
        } else {
            sorted
        }

        HomeUiState(
            searchQuery = query,
            selectedFilter = filter,
            sortOption = sort,
            featuredEvent = featured,
            upcomingEvents = remainingList,
            counts = counts,
            eventToDelete = eventToDelete,
            showSeconds = showSecs,
            isLoading = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = HomeUiState(isLoading = true)
    )

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
        _featuredIndex.value = 0
    }

    fun onFilterSelect(filter: FilterOption) {
        _selectedFilter.value = filter
        _featuredIndex.value = 0
    }

    fun onSortChange(sort: SortOption) {
        _sortOption.value = sort
        _featuredIndex.value = 0
        viewModelScope.launch {
            preferences.setDefaultSort(sort.name)
        }
    }

    fun onNextFeatured() {
        _featuredIndex.value += 1
    }

    fun onPrevFeatured() {
        _featuredIndex.value -= 1
    }

    fun toggleFavorite(event: CountdownEvent) {
        viewModelScope.launch {
            repository.toggleFavorite(event.id, event.isFavorite)
            WidgetUpdateHelper.updateAllWidgets(getApplication())
        }
    }

    fun requestDelete(event: CountdownEvent) {
        _eventToDelete.value = event
    }

    fun dismissDelete() {
        _eventToDelete.value = null
    }

    fun confirmDelete() {
        val event = _eventToDelete.value ?: return
        viewModelScope.launch {
            repository.deleteEventById(event.id)
            ReminderScheduler.cancelReminder(getApplication(), event.id)
            WidgetUpdateHelper.handleEventDeletedSync(getApplication(), event.id)
            _eventToDelete.value = null
        }
    }
}
