package com.example.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.NoteEntity
import com.example.data.NoteRepository
import com.example.data.XiaomiRichTextParser
import com.example.security.NoteBackupCrypto
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class NoteViewModel(private val repository: NoteRepository) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isGridView = MutableStateFlow(true)
    val isGridView: StateFlow<Boolean> = _isGridView.asStateFlow()

    // Screen state: null = List View, non-null = Note detail editor
    private val _editingNote = MutableStateFlow<NoteEntity?>(null)
    val editingNote: StateFlow<NoteEntity?> = _editingNote.asStateFlow()

    // Live search and pin-sort notes (Combining DB notes flow with search input flow)
    val notes: StateFlow<List<NoteEntity>> = combine(repository.allNotes, _searchQuery) { allNotes, query ->
        if (query.isBlank()) {
            allNotes
        } else {
            allNotes.filter { note ->
                val plainText = XiaomiRichTextParser.toPlainText(note.snippet)
                plainText.contains(query, ignoreCase = true) || 
                note.title.contains(query, ignoreCase = true)
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleLayout() {
        _isGridView.value = !_isGridView.value
    }

    fun startEditing(note: NoteEntity) {
        _editingNote.value = note
    }

    fun startNewNote() {
        val currentTime = System.currentTimeMillis()
        // Generate a 17-digit randomized number string to match Xiaomi note ID format
        val randomSuffix = (1000..9999).random().toString()
        val randomId = "$currentTime$randomSuffix"
        val blankNote = NoteEntity(
            id = randomId,
            snippet = "",
            modifyDate = currentTime,
            createDate = currentTime,
            colorId = 0,
            status = "normal",
            extraInfo = "{\"title\":\"\"}"
        )
        _editingNote.value = blankNote
    }

    fun updateActiveNoteSnippetAndTitle(newSnippet: String, newTitle: String) {
        val current = _editingNote.value ?: return
        
        val extraJson = try {
            val json = if (current.extraInfo.isNotBlank()) JSONObject(current.extraInfo) else JSONObject()
            json.put("title", newTitle)
            json.toString()
        } catch (e: Exception) {
            "{\"title\":\"${newTitle.replace("\"", "\\\"")}\"}"
        }

        _editingNote.value = current.copy(
            snippet = newSnippet,
            extraInfo = extraJson,
            modifyDate = System.currentTimeMillis()
        )
    }

    fun updateActiveNoteColor(colorId: Int) {
        val current = _editingNote.value ?: return
        _editingNote.value = current.copy(
            colorId = colorId,
            modifyDate = System.currentTimeMillis()
        )
    }

    fun toggleCheckbox(note: NoteEntity, index: Int, checked: Boolean) {
        val parsedLines = XiaomiRichTextParser.parse(note.snippet).toMutableList()
        if (index >= 0 && index < parsedLines.size) {
            val currentLine = parsedLines[index]
            if (currentLine.isCheckbox) {
                parsedLines[index] = currentLine.copy(checked = checked)
                val newSnippet = XiaomiRichTextParser.toRichTextFromLines(parsedLines)
                viewModelScope.launch {
                    val updatedNote = note.copy(
                        snippet = newSnippet,
                        modifyDate = System.currentTimeMillis()
                    )
                    repository.insert(updatedNote)
                }
            }
        }
    }

    fun toggleActiveNoteSticky() {
        val current = _editingNote.value ?: return
        val newStickyTime = if (current.stickyTime > 0) 0L else System.currentTimeMillis()
        _editingNote.value = current.copy(
            stickyTime = newStickyTime,
            modifyDate = System.currentTimeMillis()
        )
    }

    fun saveActiveNote() {
        val current = _editingNote.value ?: return
        // Only save if there's actually some content to store
        if (current.snippet.isNotBlank() || current.title.isNotBlank()) {
            viewModelScope.launch {
                repository.insert(current)
            }
        }
        _editingNote.value = null
    }

    fun discardOrCancelActiveNote() {
        _editingNote.value = null
    }

    fun deleteActiveNote() {
        val current = _editingNote.value ?: return
        viewModelScope.launch {
            repository.deleteById(current.id)
        }
        _editingNote.value = null
    }

    fun deleteNoteDirectly(id: String) {
        viewModelScope.launch {
            repository.deleteById(id)
        }
    }

    fun toggleStickyDirectly(note: NoteEntity) {
        val newStickyTime = if (note.stickyTime > 0) 0L else System.currentTimeMillis()
        viewModelScope.launch {
            repository.insert(note.copy(stickyTime = newStickyTime))
        }
    }

    suspend fun getExportJsonString(): String {
        val list = repository.allNotes.first()
        val jsonArray = JSONArray()
        list.forEach { note ->
            jsonArray.put(note.toJsonObject())
        }
        return jsonArray.toString(4)
    }

    suspend fun importNotesFromJson(jsonString: String): Result<Int> {
        return try {
            val trimmed = jsonString.trim()
            if (trimmed.isBlank()) {
                return Result.failure(IllegalArgumentException("备份内容为空"))
            }
            val jsonArray = JSONArray(trimmed)
            val tempNotes = buildList {
                for (i in 0 until jsonArray.length()) {
                    add(NoteEntity.fromJsonObject(jsonArray.getJSONObject(i)))
                }
            }
            if (tempNotes.isNotEmpty()) {
                repository.insertNotes(tempNotes)
            }
            Result.success(tempNotes.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun exportEncryptedBackup(password: String): Result<ByteArray> {
        return try {
            val jsonBytes = getExportJsonString().toByteArray(Charsets.UTF_8)
            Result.success(NoteBackupCrypto.encrypt(jsonBytes, password))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun importBackupFile(fileBytes: ByteArray, password: String): Result<Int> {
        return try {
            val jsonString = when {
                NoteBackupCrypto.isEncrypted(fileBytes) -> {
                    NoteBackupCrypto.decrypt(fileBytes, password).toString(Charsets.UTF_8)
                }
                else -> {
                    val text = fileBytes.toString(Charsets.UTF_8).trim()
                    if (text.startsWith("[")) {
                        text
                    } else {
                        return Result.failure(IllegalArgumentException("无法识别备份格式，请检查文件或密码"))
                    }
                }
            }
            importNotesFromJson(jsonString)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Xiaomi Cloud Sync (list + details) ---
    enum class SyncPhase { FETCHING_LIST, FETCHING_DETAILS }

    sealed class SyncState {
        object Idle : SyncState()
        data class Syncing(
            val phase: SyncPhase,
            val totalCount: Int,
            val currentProgress: Int,
            val successCount: Int,
            val failureCount: Int,
            val currentNoteTitle: String,
            val listFetchedCount: Int = 0
        ) : SyncState()
        data class Success(
            val listCount: Int,
            val detailSuccessCount: Int,
            val detailFailureCount: Int
        ) : SyncState()
        data class Error(val message: String) : SyncState()
    }

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private var syncJob: kotlinx.coroutines.Job? = null

    private fun buildMiCloudRequest(url: String, cookie: String): okhttp3.Request {
        return okhttp3.Request.Builder()
            .url(url)
            .header("Cookie", cookie)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36")
            .header("Accept", "*/*")
            .header("Referer", "https://i.mi.com/note/h5")
            .build()
    }

    private fun parseMiCloudResponse(bodyText: String): JSONObject {
        val json = JSONObject(bodyText)
        val code = json.optInt("code", -1)
        val resultStatus = json.optString("result", "")
        if (code != 0 || resultStatus != "ok") {
            val desc = json.optString("description", "请求失败，请检查 Cookie 是否有效")
            throw IllegalStateException(desc)
        }
        return json
    }

    private suspend fun fetchAllNotesFromCloud(
        client: okhttp3.OkHttpClient,
        cleanCookie: String
    ): List<NoteEntity> {
        val allNotes = mutableListOf<NoteEntity>()
        var syncTag: String? = null
        var lastPage = false

        while (!lastPage && kotlinx.coroutines.currentCoroutineContext().isActive) {
            val ts = System.currentTimeMillis()
            val url = buildString {
                append("https://i.mi.com/note/full/page?ts=$ts&limit=200")
                if (!syncTag.isNullOrBlank()) append("&syncTag=$syncTag")
            }

            val request = buildMiCloudRequest(url, cleanCookie)
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("获取便签列表失败 (HTTP ${response.code})")
                }
                val bodyText = response.body?.string()
                    ?: throw IllegalStateException("获取便签列表失败：响应为空")
                val json = parseMiCloudResponse(bodyText)
                val data = json.optJSONObject("data")
                    ?: throw IllegalStateException("获取便签列表失败：数据格式异常")

                val entries = data.optJSONArray("entries") ?: JSONArray()
                for (i in 0 until entries.length()) {
                    allNotes.add(NoteEntity.fromJsonObject(entries.getJSONObject(i)))
                }
                lastPage = data.optBoolean("lastPage", true)
                syncTag = data.optString("syncTag", null).takeIf { it.isNotBlank() }
            }

            if (!lastPage) kotlinx.coroutines.delay(250)
        }
        return allNotes
    }

    fun startCloudSync(cookie: String) {
        syncJob?.cancel()
        syncJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val cleanCookie = cookie.replace("\n", "").replace("\r", "").trim()
            if (cleanCookie.isBlank()) {
                _syncState.value = SyncState.Error("请先粘贴 Cookie")
                return@launch
            }

            _syncState.value = SyncState.Syncing(
                phase = SyncPhase.FETCHING_LIST,
                totalCount = 0,
                currentProgress = 0,
                successCount = 0,
                failureCount = 0,
                currentNoteTitle = ""
            )

            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val allNotes = try {
                fetchAllNotesFromCloud(client, cleanCookie)
            } catch (e: Exception) {
                _syncState.value = SyncState.Error(e.message ?: "获取便签列表失败")
                return@launch
            }

            if (!isActive) return@launch

            if (allNotes.isEmpty()) {
                _syncState.value = SyncState.Error("云端没有便签，或 Cookie 无权访问便签数据")
                return@launch
            }

            repository.insertNotes(allNotes)

            _syncState.value = SyncState.Syncing(
                phase = SyncPhase.FETCHING_DETAILS,
                totalCount = allNotes.size,
                currentProgress = 0,
                successCount = 0,
                failureCount = 0,
                currentNoteTitle = "",
                listFetchedCount = allNotes.size
            )

            var success = 0
            var failure = 0

            for ((index, note) in allNotes.withIndex()) {
                if (!isActive) break

                val displayTitle = if (note.title.isNotBlank()) note.title else "未命名便签"
                _syncState.value = SyncState.Syncing(
                    phase = SyncPhase.FETCHING_DETAILS,
                    totalCount = allNotes.size,
                    currentProgress = index,
                    successCount = success,
                    failureCount = failure,
                    currentNoteTitle = displayTitle,
                    listFetchedCount = allNotes.size
                )

                try {
                    val ts = System.currentTimeMillis()
                    val url = "https://i.mi.com/note/note/${note.id}/?ts=$ts"
                    val request = buildMiCloudRequest(url, cleanCookie)

                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            failure++
                        } else {
                            val bodyText = response.body?.string()
                            if (bodyText.isNullOrBlank()) {
                                failure++
                            } else {
                                val json = parseMiCloudResponse(bodyText)
                                val data = json.optJSONObject("data")
                                val entry = data?.optJSONObject("entry")
                                if (entry != null) {
                                    val fullText = entry.optString("content", entry.optString("snippet", ""))
                                    val modifyDate = entry.optLong("modifyDate", note.modifyDate)
                                    val colorId = entry.optInt("colorId", note.colorId)
                                    val status = entry.optString("status", note.status)
                                    val extraInfoResponse = entry.optString("extraInfo", note.extraInfo)
                                    val settingObj = entry.optJSONObject("setting")
                                    val stickyTime = settingObj?.optLong("stickyTime", note.stickyTime) ?: note.stickyTime
                                    val themeId = settingObj?.optInt("themeId", note.themeId) ?: note.themeId
                                    val version = settingObj?.optInt("version", note.version) ?: note.version

                                    repository.insert(
                                        note.copy(
                                            snippet = fullText,
                                            modifyDate = modifyDate,
                                            colorId = colorId,
                                            status = status,
                                            stickyTime = stickyTime,
                                            themeId = themeId,
                                            version = version,
                                            extraInfo = extraInfoResponse
                                        )
                                    )
                                    success++
                                } else {
                                    failure++
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    failure++
                }

                kotlinx.coroutines.delay(250)
            }

            if (isActive) {
                _syncState.value = SyncState.Success(
                    listCount = allNotes.size,
                    detailSuccessCount = success,
                    detailFailureCount = failure
                )
            }
        }
    }

    fun cancelCloudSync() {
        syncJob?.cancel()
        _syncState.value = SyncState.Idle
    }

    fun resetSyncState() {
        _syncState.value = SyncState.Idle
    }
}

class NoteViewModelFactory(private val repository: NoteRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(NoteViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return NoteViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
