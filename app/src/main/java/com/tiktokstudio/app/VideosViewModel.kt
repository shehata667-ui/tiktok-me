package com.tiktokstudio.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VideosViewModel(app: Application) : AndroidViewModel(app) {
    val videos = mutableStateListOf<VideoInfo>()
    var selected by mutableStateOf(setOf<Uri>())
        private set
    var loading by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)

    fun addVideos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            loading = true
            var failed = 0
            for (u in uris) {
                if (videos.any { it.uri == u }) continue
                // نحافظ على صلاحية الوصول للملف (مهم لاستئناف المهام لاحقًا)
                runCatching {
                    getApplication<Application>().contentResolver
                        .takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val info = withContext(Dispatchers.IO) { readVideoInfo(getApplication(), u) }
                if (info == null) failed++ else { videos.add(info); selected = selected + u }
            }
            loading = false
            if (failed > 0) message = "تعذّر قراءة $failed ملف (مش فيديو صالح أو تالف)"
        }
    }

    fun toggle(uri: Uri) { selected = if (uri in selected) selected - uri else selected + uri }
    fun selectAll() { selected = videos.map { it.uri }.toSet() }
    fun clearSelection() { selected = emptySet() }
    fun removeSelected() { videos.removeAll { it.uri in selected }; selected = emptySet() } // يشيلها من القائمة فقط، مش من الهاتف
}
