package dev.spacechat

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dev.spacechat.data.*
import dev.spacechat.network.ApiFailure
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@OptIn(ExperimentalCoroutinesApi::class)
class SpaceViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    private val app = application as SpaceChatApplication
    private val repo = app.repository
    val settings = app.settings.state
    // Transient edit survives Activity rotation, never saved into an instance-state Bundle.
    var unsavedApiKey: String? = null
    val selectedId = saved.getStateFlow<String?>("chat", null)
    val screen = saved.getStateFlow("screen", "home")
    val query = saved.getStateFlow("query", "")
    val draft = saved.getStateFlow("draft", "")
    val attachment = saved.getStateFlow<String?>("attachment", null)
    val editing = saved.getStateFlow<Long?>("editing", null)
    val chats = query.flatMapLatest { repo.dao.chats(it) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val messages = selectedId.flatMapLatest { id -> if(id == null) flowOf(emptyList()) else repo.dao.messages(id) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val chat = selectedId.flatMapLatest { id -> if(id == null) flowOf(null) else repo.dao.observeChat(id) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    private val _ready = MutableStateFlow(false); val ready = _ready.asStateFlow()
    private val _storageError = MutableStateFlow(false); val storageError = _storageError.asStateFlow()
    private val _error = MutableStateFlow<String?>(null); val error = _error.asStateFlow()
    private val _busy = MutableStateFlow(false); val busy = _busy.asStateFlow()
    private val _toolsBusy = MutableStateFlow(false); val toolsBusy = _toolsBusy.asStateFlow()
    private val _models = MutableStateFlow<List<String>>(emptyList()); val models = _models.asStateFlow()
    private val _tokens = MutableStateFlow(""); val tokens = _tokens.asStateFlow()
    private val _retrySeconds = MutableStateFlow(0L); val retrySeconds = _retrySeconds.asStateFlow()
    private var generation: Job? = null
    private var cooldown: Job? = null
    private var probe: Job? = null

    init { viewModelScope.launch {
        try { app.settings.load() } catch(_: Exception) { _storageError.value = true; _error.value = "Не удалось расшифровать настройки. Сбросьте их в настройках; история сохранится." }
        try { repo.dao.recoverInterrupted() } catch(_: Exception) { _error.value = "Не удалось открыть локальную историю." }
        _ready.value = true
    } }

    fun navigate(screen: String) { saved["screen"] = screen }
    fun setQuery(value: String) { saved["query"] = value }
    fun setDraft(value: String) { saved["draft"] = value }
    fun dismissError() { _error.value = null }
    fun notifyError(message: String) { _error.value = message }
    private fun action(block: suspend () -> Unit) = viewModelScope.launch {
        try { block() } catch(e: CancellationException) { throw e } catch(e: Exception) { _error.value = safeError(e) }
    }
    private fun safeError(e: Exception): String = when(e) {
        is ApiFailure -> e.message ?: "Ошибка API."
        is IllegalArgumentException, is IllegalStateException -> e.message?.takeIf { !it.contains(settings.value.apiKey.takeIf(String::isNotBlank) ?: "\u0000") } ?: "Проверьте настройки и вложение."
        else -> "Не удалось выполнить действие. Проверьте сеть, свободное место и настройки."
    }
    fun openChat(id: String) { if(selectedId.value != id) { setDraft(""); saved["editing"] = null; saved["attachment"] = null }; saved["chat"] = id; navigate("chat") }
    fun newChat() { if(_busy.value) { notifyError("Сначала остановите генерацию."); return }; action { openChat(repo.newChat(settings.value.model)) } }
    fun rename(id: String, title: String) { action { repo.dao.rename(id, title.trim().ifBlank { "Диалог" }.take(120)) } }
    fun delete(id: String) { if(_busy.value) return; action { repo.delete(id); if(selectedId.value == id) { saved["chat"] = null; navigate("home") } } }
    fun clearHistory() { if(_busy.value) return; action { repo.clear(); saved["chat"] = null; saved["attachment"] = null; saved["editing"] = null; setDraft(""); navigate("settings") } }
    fun setModel(model: String) { val id = selectedId.value ?: return; if(_busy.value) return; action { repo.dao.setModel(id, model.trim()) } }
    fun saveSettings(value: ApiSettings, done: () -> Unit) { action {
        require(!_storageError.value) { "Сначала сбросьте недоступные настройки." }
        if(value.baseUrl.isNotBlank()) dev.spacechat.network.OpenAiProvider().endpoint(value.baseUrl, "models")
        require(value.timeoutSeconds in 10..600) { "Таймаут: от 10 до 600 секунд." }
        app.settings.save(value); unsavedApiKey = null; _models.value = emptyList(); done()
    } }
    fun resetSettings() { action { probe?.cancel(); app.settings.reset(); unsavedApiKey = null; _storageError.value = false; _models.value = emptyList(); _error.value = "Настройки сброшены." } }
    fun fetchModels(value: ApiSettings) {
        if(_toolsBusy.value || _retrySeconds.value > 0) return
        probe = viewModelScope.launch {
            _toolsBusy.value = true
            try { val result = repo.provider.models(value); _models.value = result; _error.value = if(result.isEmpty()) "API доступен, но список моделей пуст. Введите Model ID вручную." else "Подключение успешно: моделей ${result.size}." }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { _error.value = safeError(e); if(e is ApiFailure) startCooldown(e) }
            finally { _toolsBusy.value = false }
        }
    }
    fun attach(uri: Uri) {
        if(_busy.value) return
        action {
            val path = repo.attachments.importAttachment(uri, settings.value.imagesEnabled)
            removeAttachment()
            saved["attachment"] = path
        }
    }
    fun removeAttachment() {
        val path = attachment.value ?: return
        if(editing.value == null) action { repo.images.delete(path) }
        saved["attachment"] = null
    }
    fun edit(message: MessageEntity) {
        if(_busy.value) return
        saved["editing"] = message.id; setDraft(message.text); saved["attachment"] = message.imagePath
    }
    fun cancelEdit() { saved["editing"] = null; saved["attachment"] = null; setDraft("") }

    fun send() {
        if(_busy.value || _retrySeconds.value > 0 || !_ready.value) return
        val id = selectedId.value ?: return
        val text = draft.value.trim(); val image = attachment.value
        if(text.isBlank() && image == null) return
        val editId = editing.value
        launchGeneration(id) {
            validate(id)
            if(editId != null) repo.truncate(id, editId, image)
            repo.addUser(id, text, image)
            saved["draft"] = ""; saved["attachment"] = null; saved["editing"] = null
        }
    }
    fun regenerate() {
        if(_busy.value || _retrySeconds.value > 0) return
        val id = selectedId.value ?: return
        launchGeneration(id) {
            validate(id)
            val lastUser = repo.dao.snapshot(id).lastOrNull { it.role == "user" } ?: error("Нет сообщения для повтора.")
            repo.truncate(id, lastUser.id + 1)
        }
    }
    private suspend fun validate(id: String) {
        val cfg = settings.value
        require(!_storageError.value) { "Сбросьте недоступные настройки." }
        require(cfg.apiKey.isNotBlank() && cfg.baseUrl.isNotBlank()) { "Укажите Base URL и API-ключ в настройках." }
        require(repo.dao.chat(id)?.model?.isNotBlank() == true) { "Выберите Model ID для чата." }
        dev.spacechat.network.OpenAiProvider().endpoint(cfg.baseUrl, "chat/completions")
    }
    private fun launchGeneration(id: String, prepare: suspend () -> Unit) {
        _busy.value = true; _error.value = null; _tokens.value = ""
        generation = viewModelScope.launch {
            var assistantId: Long? = null
            val output = StringBuilder()
            var status = "complete"
            try {
                prepare()
                val cfg = settings.value
                val context = repo.context(id)
                require(context.none { it.imageData != null } || cfg.imagesEnabled) { "Контекст содержит изображение. Подтвердите поддержку image_url в настройках." }
                val model = repo.dao.chat(id)?.model ?: error("Диалог удалён.")
                assistantId = repo.dao.insert(MessageEntity(chatId = id, role = "assistant", text = "", status = "streaming"))
                var lastUpdate = 0L
                repo.provider.stream(cfg, model, context).collect { delta ->
                    require(output.length + delta.length <= 2_000_000) { "Ответ слишком большой. Генерация остановлена." }
                    output.append(delta)
                    val now = System.nanoTime()
                    if(now - lastUpdate >= 50_000_000) { _tokens.value = output.toString(); lastUpdate = now }
                }
                if(output.isEmpty()) throw ApiFailure(null, message = "API завершил поток без текста. Возможно, модель использует неподдерживаемый формат ответа.")
            } catch(e: CancellationException) { status = "interrupted" }
            catch(e: Exception) { status = "error"; _error.value = safeError(e); if(e is ApiFailure) startCooldown(e) }
            finally {
                _tokens.value = output.toString()
                withContext(NonCancellable) {
                    try { assistantId?.let { repo.dao.finish(it, output.toString(), status) }; repo.dao.touch(id) }
                    catch(_: Exception) { _error.value = "Не удалось сохранить ответ. Проверьте свободное место." }
                }
                _busy.value = false
            }
        }
    }
    private fun startCooldown(error: ApiFailure) {
        if(error.status != 429) return
        cooldown?.cancel()
        cooldown = viewModelScope.launch {
            _retrySeconds.value = (error.retryAfterSeconds ?: 5).coerceIn(1, 86400)
            while(_retrySeconds.value > 0) { delay(1000); _retrySeconds.value -= 1 }
        }
    }
    fun stop() { generation?.cancel() }
}
