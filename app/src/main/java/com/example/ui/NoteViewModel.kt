package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.NoteEntity
import com.example.data.NoteImageCache
import com.example.data.NoteImageDownloadResult
import com.example.data.NoteRepository
import com.example.data.XiaomiRichTextParser
import com.example.security.NoteBackupCrypto
import com.example.ui.list.NoteListItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class NoteViewModel(
    application: Application,
    private val repository: NoteRepository
) : AndroidViewModel(application) {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private fun uiPrefs() =
        getApplication<Application>().getSharedPreferences("mi_ui_prefs", android.content.Context.MODE_PRIVATE)

    private val _isGridView = MutableStateFlow(uiPrefs().getBoolean(PREF_IS_GRID_VIEW, true))
    val isGridView: StateFlow<Boolean> = _isGridView.asStateFlow()

    // Screen state: null = List View, non-null = Note detail editor
    private val _editingNote = MutableStateFlow<NoteEntity?>(null)
    val editingNote: StateFlow<NoteEntity?> = _editingNote.asStateFlow()

    // Live search and pin-sort notes（解析/过滤放 Default，避免主线程卡顿）
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
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            // Eagerly：进编辑页时 MainActivity 停订阅，仍持续收 Room，返回才能立刻看到改动
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    private val _imageCacheTick = MutableStateFlow(0L)
    val imageCacheTick: StateFlow<Long> = _imageCacheTick.asStateFlow()

    /** 对齐官方 NoteCache：列表 bind 前预计算标题/摘要/时间；叠加 imageCacheTick 以便下图后刷新缩略图 */
    val listItems: StateFlow<List<NoteListItem>> = combine(notes, imageCacheTick) { list, _ ->
        val ctx = getApplication<Application>()
        list.map { NoteListItem.from(it, ctx) }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleLayout() {
        val next = !_isGridView.value
        _isGridView.value = next
        uiPrefs().edit().putBoolean(PREF_IS_GRID_VIEW, next).apply()
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
            clearHydrated(id)
        }
    }

    fun deleteNotesDirectly(ids: Collection<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            ids.forEach { id ->
                repository.deleteById(id)
                clearHydrated(id)
            }
        }
    }

    fun toggleStickyDirectly(note: NoteEntity) {
        val newStickyTime = if (note.stickyTime > 0) 0L else System.currentTimeMillis()
        viewModelScope.launch {
            repository.insert(note.copy(stickyTime = newStickyTime))
        }
    }

    /**
     * 备份格式 v2：便签 + 按 fileId 索引的本地图片（Base64）。
     * 兼容旧版纯 JSONArray。
     * 重活一律 Dispatchers.IO，避免 lifecycleScope.launch 默认主线程卡 ANR。
     */
    suspend fun getExportJsonString(): String = withContext(Dispatchers.IO) {
        val list = repository.allNotes.first()
        val notesArr = JSONArray()
        val imagesObj = JSONObject()
        val appContext = getApplication<Application>()

        list.forEach { note ->
            notesArr.put(note.toJsonObject())
            XiaomiRichTextParser.extractImageFileIds(note.snippet).forEach { fileId ->
                if (imagesObj.has(fileId)) return@forEach
                val file = NoteImageCache.findCachedFile(appContext, fileId) ?: return@forEach
                val b64 = NoteImageCache.readBase64(appContext, fileId) ?: return@forEach
                imagesObj.put(
                    fileId,
                    JSONObject()
                        .put("fileName", file.name)
                        .put("data", b64)
                )
            }
        }

        JSONObject()
            .put("format", BACKUP_FORMAT_V2)
            .put("notes", notesArr)
            .put("images", imagesObj)
            .toString(4)
    }

    suspend fun importNotesFromJson(jsonString: String): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val trimmed = jsonString.trim()
            if (trimmed.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("备份内容为空"))
            }

            val notesArr: JSONArray
            val imagesObj: JSONObject?
            if (trimmed.startsWith("{")) {
                val root = JSONObject(trimmed)
                notesArr = root.optJSONArray("notes")
                    ?: return@withContext Result.failure(IllegalArgumentException("备份缺少 notes 字段"))
                imagesObj = root.optJSONObject("images")
            } else {
                notesArr = JSONArray(trimmed)
                imagesObj = null
            }

            val appContext = getApplication<Application>()
            if (imagesObj != null) {
                val keys = imagesObj.keys()
                while (keys.hasNext()) {
                    val fileId = keys.next()
                    val item = imagesObj.optJSONObject(fileId) ?: continue
                    val data = item.optString("data", "")
                    if (data.isBlank()) continue
                    NoteImageCache.saveBase64(
                        appContext,
                        fileId,
                        data,
                        item.optString("fileName").takeIf { it.isNotBlank() }
                    )
                }
            }

            val tempNotes = buildList {
                for (i in 0 until notesArr.length()) {
                    add(NoteEntity.fromJsonObject(notesArr.getJSONObject(i)))
                }
            }
            if (tempNotes.isNotEmpty()) {
                repository.insertNotes(tempNotes)
                tempNotes.forEach { markHydrated(it.id) }
            }
            _imageCacheTick.value = _imageCacheTick.value + 1
            Result.success(tempNotes.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun exportEncryptedBackup(password: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val jsonBytes = getExportJsonString().toByteArray(Charsets.UTF_8)
            Result.success(NoteBackupCrypto.encrypt(jsonBytes, password))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun importBackupFile(fileBytes: ByteArray, password: String): Result<Int> =
        withContext(Dispatchers.IO) {
            try {
                val jsonString = when {
                    NoteBackupCrypto.isEncrypted(fileBytes) -> {
                        NoteBackupCrypto.decrypt(fileBytes, password).toString(Charsets.UTF_8)
                    }
                    else -> {
                        val text = fileBytes.toString(Charsets.UTF_8).trim()
                        if (text.startsWith("[") || text.startsWith("{")) {
                            text
                        } else {
                            return@withContext Result.failure(
                                IllegalArgumentException("无法识别备份格式，请检查文件或密码")
                            )
                        }
                    }
                }
                importNotesFromJson(jsonString)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // --- Xiaomi Cloud Sync ---
    // Cookie 只在同步时用；图片永久落盘后，打开便签只读本地缓存。
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
            val listFetchedCount: Int = 0,
            val imagesCachedCount: Int = 0
        ) : SyncState()
        data class Success(
            val listCount: Int,
            val detailSuccessCount: Int,
            val detailFailureCount: Int,
            val imagesCachedCount: Int,
            val remainingCount: Int,
            val cookieExpired: Boolean
        ) : SyncState()
        data class Error(val message: String) : SyncState()
    }

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private var syncJob: kotlinx.coroutines.Job? = null

    private fun syncPrefs() =
        getApplication<Application>().getSharedPreferences("mi_sync_prefs", android.content.Context.MODE_PRIVATE)

    private fun hydratedIds(): MutableSet<String> =
        syncPrefs().getStringSet(PREF_HYDRATED_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()

    private fun saveHydratedIds(ids: Set<String>) {
        syncPrefs().edit().putStringSet(PREF_HYDRATED_IDS, HashSet(ids)).apply()
    }

    private fun markHydrated(noteId: String) {
        val ids = hydratedIds()
        if (ids.add(noteId)) saveHydratedIds(ids)
    }

    private fun clearHydrated(noteId: String) {
        val ids = hydratedIds()
        if (ids.remove(noteId)) saveHydratedIds(ids)
    }

    private fun buildMiCloudRequest(url: String, cookie: String): okhttp3.Request {
        return okhttp3.Request.Builder()
            .url(url)
            .header("Cookie", cookie)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36"
            )
            .header("Accept", "*/*")
            .header("Referer", "https://i.mi.com/note/h5")
            .build()
    }

    private fun collectImageFileIds(content: String, settingObj: JSONObject?): List<String> {
        val ids = LinkedHashSet<String>()
        ids.addAll(XiaomiRichTextParser.extractImageFileIds(content))
        val dataArr = settingObj?.optJSONArray("data")
        if (dataArr != null) {
            for (i in 0 until dataArr.length()) {
                val item = dataArr.optJSONObject(i) ?: continue
                val mime = item.optString("mimeType", "")
                val fileId = item.optString("fileId", "")
                if (fileId.isNotBlank() && (mime.startsWith("image/") || mime.isBlank())) {
                    ids.add(fileId)
                }
            }
        }
        return ids.toList()
    }

    private fun hasMissingImages(content: String): Boolean {
        val appContext = getApplication<Application>()
        return XiaomiRichTextParser.extractImageFileIds(content).any { !NoteImageCache.isCached(appContext, it) }
    }

    private fun isAuthErrorMessage(message: String): Boolean {
        val m = message.lowercase()
        return m.contains("cookie") || m.contains("login") || m.contains("登录") ||
            m.contains("auth") || m.contains("未授权") || m.contains("过期") ||
            m.contains("invalid") || m.contains("401") || m.contains("403")
    }

    private fun parseMiCloudResponse(bodyText: String): JSONObject {
        val json = JSONObject(bodyText)
        val code = json.optInt("code", -1)
        val resultStatus = json.optString("result", "")
        if (code != 0 || resultStatus != "ok") {
            val desc = json.optString("description", "请求失败，请重新登录后再试")
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
                if (response.code == 401 || response.code == 403) {
                    throw IllegalStateException("登录已过期，请重新登录后再同步")
                }
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

            if (!lastPage) kotlinx.coroutines.delay(200)
        }
        return allNotes
    }

    /** 合并列表数据：已补全正文的本地便签不被短摘要覆盖 */
    private suspend fun mergeListNotes(cloudNotes: List<NoteEntity>) {
        val hydrated = hydratedIds()
        for (cloud in cloudNotes) {
            val local = repository.getNoteById(cloud.id)
            if (local == null) {
                repository.insert(cloud)
                continue
            }
            if (cloud.modifyDate > local.modifyDate) {
                clearHydrated(cloud.id)
                hydrated.remove(cloud.id)
                repository.insert(cloud)
            } else {
                val keepLocalSnippet = hydrated.contains(local.id) ||
                    local.snippet.length > cloud.snippet.length
                repository.insert(
                    local.copy(
                        colorId = cloud.colorId,
                        status = cloud.status,
                        folderId = cloud.folderId,
                        tag = cloud.tag,
                        subject = cloud.subject.ifBlank { local.subject },
                        alertDate = cloud.alertDate,
                        stickyTime = if (cloud.stickyTime != 0L) cloud.stickyTime else local.stickyTime,
                        themeId = if (cloud.themeId != 0) cloud.themeId else local.themeId,
                        extraInfo = cloud.extraInfo.ifBlank { local.extraInfo },
                        snippet = if (keepLocalSnippet) local.snippet else cloud.snippet
                    )
                )
            }
        }
    }

    private data class ImageBatchResult(
        val newlyCached: Int,
        val authExpired: Boolean
    )

    /** 同一便签内的多张图片并发下载（限流），落盘后按 fileId 展示 */
    private suspend fun downloadMissingImagesConcurrent(
        client: okhttp3.OkHttpClient,
        cookie: String,
        content: String,
        settingObj: JSONObject?
    ): ImageBatchResult = coroutineScope {
        val fileIds = collectImageFileIds(content, settingObj)
            .filter { !NoteImageCache.isCached(getApplication(), it) }
        if (fileIds.isEmpty()) return@coroutineScope ImageBatchResult(0, false)

        val appContext = getApplication<Application>()
        val semaphore = Semaphore(IMAGE_DOWNLOAD_CONCURRENCY)
        val newly = AtomicInteger(0)
        val authExpired = AtomicBoolean(false)

        fileIds.map { fileId ->
            async(Dispatchers.IO) {
                if (authExpired.get() || !isActive) return@async
                semaphore.withPermit {
                    if (authExpired.get()) return@withPermit
                    when (NoteImageCache.downloadIfNeeded(appContext, client, cookie, fileId)) {
                        NoteImageDownloadResult.Cached -> newly.incrementAndGet()
                        NoteImageDownloadResult.Failed -> Unit
                        NoteImageDownloadResult.AuthExpired -> authExpired.set(true)
                    }
                }
            }
        }.awaitAll()

        ImageBatchResult(newly.get(), authExpired.get())
    }

    /**
     * 同步：拉列表 → 逐条拉详情 → 遇图片则并发下载落盘。
     * 图片永久本地缓存，之后按 fileId 展示，不再依赖 Cookie。
     * 中途 Cookie 失效则停下，已缓存的图片仍可看；下次同步续传补缺。
     */
    fun startCloudSync(cookie: String) {
        syncJob?.cancel()
        syncJob = viewModelScope.launch(Dispatchers.IO) {
            val cleanCookie = cookie.replace("\n", "").replace("\r", "").trim()
            if (cleanCookie.isBlank()) {
                _syncState.value = SyncState.Error("请先登录小米账号")
                return@launch
            }
            // Cookie 不落盘；顺带清掉历史残留
            syncPrefs().edit().remove(PREF_COOKIE).apply()

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
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()

            val allNotes = try {
                fetchAllNotesFromCloud(client, cleanCookie)
            } catch (e: Exception) {
                _syncState.value = SyncState.Error(e.message ?: "获取便签列表失败")
                return@launch
            }

            if (!isActive) return@launch
            if (allNotes.isEmpty()) {
                _syncState.value = SyncState.Error("云端没有便签，或当前账号无法访问便签数据")
                return@launch
            }

            mergeListNotes(allNotes)

            // 首次/续传：未补全文或仍缺图片的便签
            val pending = allNotes.mapNotNull { cloud ->
                val local = repository.getNoteById(cloud.id) ?: cloud
                if (!hydratedIds().contains(local.id) || hasMissingImages(local.snippet)) local else null
            }

            if (pending.isEmpty()) {
                _imageCacheTick.value = _imageCacheTick.value + 1
                _syncState.value = SyncState.Success(
                    listCount = allNotes.size,
                    detailSuccessCount = 0,
                    detailFailureCount = 0,
                    imagesCachedCount = 0,
                    remainingCount = 0,
                    cookieExpired = false
                )
                return@launch
            }

            var success = 0
            var failure = 0
            var imagesCached = 0
            var cookieExpired = false
            // 进度分母用云端总数；已同步过的也算在分子里，避免只显示「待处理」条数造成误解
            val alreadySynced = (allNotes.size - pending.size).coerceAtLeast(0)

            for ((index, note) in pending.withIndex()) {
                if (!isActive || cookieExpired) break

                val displayTitle = if (note.title.isNotBlank()) note.title else "未命名便签"
                _syncState.value = SyncState.Syncing(
                    phase = SyncPhase.FETCHING_DETAILS,
                    totalCount = allNotes.size,
                    currentProgress = alreadySynced + index + 1,
                    successCount = success,
                    failureCount = failure,
                    currentNoteTitle = displayTitle,
                    listFetchedCount = allNotes.size,
                    imagesCachedCount = imagesCached
                )

                try {
                    var working = repository.getNoteById(note.id) ?: note
                    var settingObj: JSONObject? = null
                    val needDetail = !hydratedIds().contains(working.id)

                    if (needDetail) {
                        val ts = System.currentTimeMillis()
                        val url = "https://i.mi.com/note/note/${working.id}/?ts=$ts"
                        val request = buildMiCloudRequest(url, cleanCookie)
                        client.newCall(request).execute().use { response ->
                            if (response.code == 401 || response.code == 403) {
                                cookieExpired = true
                                return@use
                            }
                            if (!response.isSuccessful) {
                                failure++
                                return@use
                            }
                            val bodyText = response.body?.string()
                            if (bodyText.isNullOrBlank()) {
                                failure++
                                return@use
                            }
                            try {
                                val json = parseMiCloudResponse(bodyText)
                                val entry = json.optJSONObject("data")?.optJSONObject("entry")
                                if (entry == null) {
                                    failure++
                                    return@use
                                }
                                val fullText = entry.optString("content", entry.optString("snippet", ""))
                                settingObj = entry.optJSONObject("setting")
                                working = working.copy(
                                    snippet = fullText,
                                    modifyDate = entry.optLong("modifyDate", working.modifyDate),
                                    colorId = entry.optInt("colorId", working.colorId),
                                    status = entry.optString("status", working.status),
                                    extraInfo = entry.optString("extraInfo", working.extraInfo),
                                    stickyTime = settingObj?.optLong("stickyTime", working.stickyTime)
                                        ?: working.stickyTime,
                                    themeId = settingObj?.optInt("themeId", working.themeId)
                                        ?: working.themeId,
                                    version = settingObj?.optInt("version", working.version)
                                        ?: working.version
                                )
                                repository.insert(working)
                                markHydrated(working.id)
                                if (_editingNote.value?.id == working.id) {
                                    _editingNote.value = working
                                }
                            } catch (e: Exception) {
                                if (isAuthErrorMessage(e.message.orEmpty())) {
                                    cookieExpired = true
                                } else {
                                    failure++
                                }
                                return@use
                            }
                        }
                        if (cookieExpired) break
                        if (!hydratedIds().contains(working.id) && settingObj == null) {
                            // 详情失败已计入 failure
                            continue
                        }
                    }

                    val imgResult = downloadMissingImagesConcurrent(
                        client, cleanCookie, working.snippet, settingObj
                    )
                    imagesCached += imgResult.newlyCached
                    if (imgResult.authExpired) {
                        cookieExpired = true
                        break
                    }
                    success++
                } catch (e: Exception) {
                    if (isAuthErrorMessage(e.message.orEmpty())) {
                        cookieExpired = true
                        break
                    }
                    failure++
                }

                kotlinx.coroutines.delay(150)
            }

            var remaining = 0
            for (cloud in allNotes) {
                val local = repository.getNoteById(cloud.id) ?: continue
                if (!hydratedIds().contains(local.id) || hasMissingImages(local.snippet)) {
                    remaining++
                }
            }

            _imageCacheTick.value = _imageCacheTick.value + 1
            if (isActive) {
                _syncState.value = SyncState.Success(
                    listCount = allNotes.size,
                    detailSuccessCount = success,
                    detailFailureCount = failure,
                    imagesCachedCount = imagesCached,
                    remainingCount = remaining,
                    cookieExpired = cookieExpired
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

    /** 本地插入/删除图片后刷新编辑页图片展示 */
    fun notifyImageCacheChanged() {
        _imageCacheTick.value = _imageCacheTick.value + 1
    }

    companion object {
        private const val PREF_COOKIE = "pasted_cookie"
        private const val PREF_HYDRATED_IDS = "hydrated_note_ids"
        private const val PREF_IS_GRID_VIEW = "is_grid_view"
        private const val BACKUP_FORMAT_V2 = "minote-backup-v2"
        private const val IMAGE_DOWNLOAD_CONCURRENCY = 4
    }
}

class NoteViewModelFactory(
    private val application: Application,
    private val repository: NoteRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(NoteViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return NoteViewModel(application, repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
