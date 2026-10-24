package com.vj.tubegrab

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    var query by mutableStateOf("")
        private set
    var suggestions by mutableStateOf<List<String>>(emptyList())
        private set
    var results by mutableStateOf<List<VideoItem>>(emptyList())
        private set
    var searching by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var hasSearched by mutableStateOf(false)
        private set

    val engineStatus = Engine.status

    private var suggestJob: Job? = null
    private var searchJob: Job? = null

    fun onQueryChange(text: String) {
        query = text
        suggestJob?.cancel()
        if (text.isBlank()) {
            suggestions = emptyList()
            return
        }
        suggestJob = viewModelScope.launch {
            delay(250) // wait until typing pauses
            suggestions = Engine.suggestions(text)
        }
    }

    fun search(text: String = query) {
        val q = text.trim()
        if (q.isEmpty()) return
        query = q
        suggestJob?.cancel()
        suggestions = emptyList()
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            searching = true
            error = null
            hasSearched = true
            try {
                results = Engine.search(getApplication(), q)
                if (results.isEmpty()) error = "No results"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                results = emptyList()
                error = Engine.cleanError(e)
            } finally {
                searching = false
            }
        }
    }

    fun clearSuggestions() {
        suggestJob?.cancel()
        suggestions = emptyList()
    }

    fun download(item: VideoItem, format: DlFormat) =
        DownloadCenter.start(getApplication(), item, format)

    fun updateEngine() {
        viewModelScope.launch {
            try { Engine.update(getApplication()) } catch (_: Exception) {}
        }
    }

    /** Pull the first link out of shared text like "Check this out https://youtu.be/xyz". */
    fun handleShared(text: String) {
        val url = Regex("https?://\\S+").find(text)?.value ?: text
        search(url)
    }
}
