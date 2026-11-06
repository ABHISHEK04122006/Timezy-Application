package com.timezy.widget.data.repository

import android.content.Context
import androidx.lifecycle.asFlow
import com.timezy.widget.CountdownApplication
import com.timezy.widget.data.database.EventDao
import com.timezy.widget.data.database.EventEntity
import com.timezy.widget.data.model.CountdownEvent
import com.timezy.widget.widget.WidgetUpdateHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class EventRepositoryImpl(
    private val eventDao: EventDao,
    private val context: Context? = null
) : EventRepository {

    override fun getAllEventsFlow(): Flow<List<CountdownEvent>> {
        return eventDao.allEventsLiveData.asFlow().map { list ->
            list?.map { it.toDomain() } ?: emptyList()
        }
    }

    override suspend fun getAllEvents(): List<CountdownEvent> = withContext(Dispatchers.IO) {
        eventDao.allEventsSync?.map { it.toDomain() } ?: emptyList()
    }

    override fun getAllEventsSync(): List<CountdownEvent> {
        return eventDao.allEventsSync?.map { it.toDomain() } ?: emptyList()
    }

    override fun getEventByIdFlow(id: Long): Flow<CountdownEvent?> {
        return eventDao.getEventByIdLiveData(id).asFlow().map { it?.toDomain() }
    }

    override suspend fun getEventById(id: Long): CountdownEvent? = withContext(Dispatchers.IO) {
        eventDao.getEventByIdSync(id)?.toDomain()
    }

    override fun getEventByIdSync(id: Long): CountdownEvent? {
        return eventDao.getEventByIdSync(id)?.toDomain()
    }

    override suspend fun insertEvent(event: CountdownEvent): Long = withContext(Dispatchers.IO) {
        eventDao.insertEvent(EventEntity.fromDomain(event))
    }

    override suspend fun updateEvent(event: CountdownEvent): Unit = withContext(Dispatchers.IO) {
        eventDao.updateEvent(EventEntity.fromDomain(event))
    }

    override suspend fun deleteEvent(event: CountdownEvent): Unit = withContext(Dispatchers.IO) {
        eventDao.deleteEvent(EventEntity.fromDomain(event))
        val appContext = context ?: try { CountdownApplication.instance } catch (_: Exception) { null }
        if (appContext != null) {
            WidgetUpdateHelper.handleEventDeletedSync(appContext, event.id)
        }
    }

    override suspend fun deleteEventById(id: Long) = withContext(Dispatchers.IO) {
        eventDao.deleteEventById(id)
        val appContext = context ?: try { CountdownApplication.instance } catch (_: Exception) { null }
        if (appContext != null) {
            WidgetUpdateHelper.handleEventDeletedSync(appContext, id)
        }
        return@withContext
    }

    override suspend fun toggleFavorite(id: Long, currentFavorite: Boolean) = withContext(Dispatchers.IO) {
        eventDao.updateFavorite(id, !currentFavorite)
        return@withContext
    }

    override suspend fun clearAllEvents() = withContext(Dispatchers.IO) {
        eventDao.clearAllEvents()
        val appContext = context ?: try { CountdownApplication.instance } catch (_: Exception) { null }
        if (appContext != null) {
            WidgetUpdateHelper.handleAllEventsClearedSync(appContext)
        }
        return@withContext
    }
}
