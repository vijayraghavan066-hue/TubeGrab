package com.vj.tubegrab

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Results + loading state for one search tab. */
class SearchPane {
    var results by mutableStateOf<List<VideoItem>>(emptyList())
    var searching by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var hasSearched by mutableStateOf(false)
    var lastQuery by mutableStateOf("")
    var job: Job? = null
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        const val TAB_YOUTUBE = 0
        const val TAB_OTHER = 1
        const val TAB_DOWNLOADS = 2
    }

    var tab by mutableIntStateOf(TAB_YOUTUBE)
        private set
    var query by mutableStateOf("")
        private set
    var suggestions by mutableStateOf<List<String>>(emptyList())
        private set

    val youtube = SearchPane()
    val other = SearchPane()

    val engineStatus = Engine.status

    private var suggestJob: Job? = null

    val updateState = Updater.state

    init {
        viewModelScope.launch { Updater.check(getApplication(), silent = true) }
    }

    fun checkForUpdate() {
        viewModelScope.launch { Updater.check(getApplication(), silent = false) }
    }

    fun downloadUpdate(release: Updater.Release) {
        viewModelScope.launch { Updater.download(getApplication(), release) }
    }

    fun selectTab(t: Int) {
        tab = t
        clearSuggestions()
        // Switching to "Other sites" with a fresh query runs that search automatically
        if (t == TAB_OTHER && query.isNotBlank() && !Engine.looksLikeUrl(query) && query.trim() != other.lastQuery) {
            search()
        }
    }

    fun onQueryChange(text: String) {
        query = text
        suggestJob?.cancel()
        if (text.isBlank() || tab != TAB_YOUTUBE) {
            suggestions = emptyList()
            return
        }
        suggestJob = viewModelScope.launch {
            delay(250) // wait until typing pauses
            suggestions = Engine.suggestions(text)
        }
    }

    /** Runs the search for whichever tab is open (a pasted link always goes to the YouTube/link tab). */
    fun search(text: String = query) {
        val q = text.trim()
        if (q.isEmpty()) return
        query = q
        clearSuggestions()

        val isLink = Engine.looksLikeUrl(q)
        if (tab == TAB_DOWNLOADS || isLink) tab = TAB_YOUTUBE
        val pane = if (tab == TAB_OTHER) other else youtube
        val useOther = tab == TAB_OTHER

        pane.job?.cancel()
        pane.job = viewModelScope.launch {
            pane.searching = true
            pane.error = null
            pane.hasSearched = true
            pane.lastQuery = q
            try {
                pane.results = if (useOther) Engine.searchOtherSites(q) else Engine.search(getApplication(), q)
                if (pane.results.isEmpty()) pane.error = "No results"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                pane.results = emptyList()
                pane.error = Engine.cleanError(e)
            } finally {
                pane.searching = false
            }
        }
    }

    fun clearSuggestions() {
        suggestJob?.cancel()
        suggestions = emptyList()
    }

    fun download(item: VideoItem, format: DlFormat) =
        DownloadCenter.start(getApplication(), item, format, temporary = AppSettings.temporary.value)

    fun updateEngine() {
        viewModelScope.launch {
            try { Engine.update(getApplication()) } catch (_: Exception) {}
        }
    }

    /** Pull the first link out of shared text like "Check this out https://youtu.be/xyz". */
    fun handleShared(text: String) {
        val url = Regex("https?://\\S+").find(text)?.value ?: text
        tab = TAB_YOUTUBE
        search(url)
    }
}
