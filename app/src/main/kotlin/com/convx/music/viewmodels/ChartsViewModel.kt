/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.music.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.convx.music.utils.isDevotionalItem
import com.convx.music.utils.isDevotionalText
import com.music.innertube.YouTube
import com.music.innertube.pages.ChartsPage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChartsViewModel @Inject constructor() : ViewModel() {
    private val _chartsPage = MutableStateFlow<ChartsPage?>(null)
    val chartsPage = _chartsPage.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private fun filterCharts(page: ChartsPage): ChartsPage {
        val filteredSections = page.sections.mapNotNull { section ->
            if (isDevotionalText(section.title)) return@mapNotNull null
            val cleanItems = section.items.filterNot { isDevotionalItem(it) }
            if (cleanItems.isEmpty()) null else section.copy(items = cleanItems)
        }
        return page.copy(sections = filteredSections)
    }

    // IO, not Main: Ktor decodes the (large) charts JSON on the calling dispatcher,
    // which froze the UI past the ANR limit on cold start.
    fun loadCharts() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _error.value = null
            
            YouTube.getChartsPage()
                .onSuccess { page ->
                    _chartsPage.value = filterCharts(page)
                }
                .onFailure { e ->
                    _error.value = "Failed to load charts: ${e.message}"
                }
            
            _isLoading.value = false
        }
    }

    fun loadMore() {
        viewModelScope.launch(Dispatchers.IO) {
            _chartsPage.value?.continuation?.let { continuation ->
                _isLoading.value = true
                YouTube.getChartsPage(continuation)
                    .onSuccess { newPage ->
                        val filteredNew = filterCharts(newPage)
                        _chartsPage.value = _chartsPage.value?.copy(
                            sections = _chartsPage.value?.sections.orEmpty() + filteredNew.sections,
                            continuation = newPage.continuation
                        )
                    }
                    .onFailure { e ->
                        _error.value = "Failed to load more: ${e.message}"
                    }
                _isLoading.value = false
            }
        }
    }
}
