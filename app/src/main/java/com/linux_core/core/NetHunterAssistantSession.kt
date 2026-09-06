package com.linux_core.core

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.service.voice.VoiceInteractionSession
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

import java.util.concurrent.Executors

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

class NetHunterAssistantSession(context: Context) : VoiceInteractionSession(context), TextToSpeech.OnInitListener {
    companion object {
        private const val TAG = "NetHunterAssistantSess"
    }

    private class AssistantLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val store = ViewModelStore()
        private val controller = SavedStateRegistryController.create(this)

        init {
            controller.performRestore(null)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        }

        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val viewModelStore: ViewModelStore get() = store
        override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry

        fun onStart() {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        fun onStop() {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }

        fun onDestroy() {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            store.clear()
        }
    }

    private val assistantLifecycleOwner = AssistantLifecycleOwner()
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    // UI States
    private val messages = mutableStateListOf<ChatMessage>()
    private var statusState = mutableStateOf("Initializing...")
    private var isListeningState = mutableStateOf(false)
    private var inputTextState = mutableStateOf("")

    override fun onCreate() {
        super.onCreate()
        
        // Window settings for assistant overlay
        val dialog = window ?: return
        val win = dialog.window ?: return
        
        // Ensure the window is focusable and alt-focusable is cleared so keyboard doesn't overlap the overlay
        win.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        win.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        @Suppress("DEPRECATION")
        win.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val lp = win.attributes
        lp.gravity = Gravity.BOTTOM or Gravity.FILL_HORIZONTAL
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.MATCH_PARENT
        win.attributes = lp

        // Initialize TTS
        tts = TextToSpeech(context, this)

        // Add system greeting message
        messages.add(ChatMessage("System ready. Type a command or tap the microphone to speak.", isUser = false))
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.getDefault()
            ttsReady = true
            Log.i(TAG, "TTS initialized successfully")
        } else {
            Log.e(TAG, "TTS initialization failed")
        }
    }

    override fun onCreateContentView(): View {
        val composeView = ComposeView(context).apply {
            // Set the owners to prevent ViewTree lifecycle crashes in Compose
            setViewTreeLifecycleOwner(assistantLifecycleOwner)
            setViewTreeViewModelStoreOwner(assistantLifecycleOwner)
            setViewTreeSavedStateRegistryOwner(assistantLifecycleOwner)

            setContent {
                AssistantOverlayLayout()
            }
        }
        return composeView
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        Log.i(TAG, "onShow called with showFlags: $showFlags")
        super.onShow(args, showFlags)
        assistantLifecycleOwner.onStart()
        
        // Start listening when assistant shown
        handler.post {
            Log.i(TAG, "Posting startSpeechRecognition task")
            startSpeechRecognition()
        }
    }

    override fun onHide() {
        Log.i(TAG, "onHide called")
        super.onHide()
        assistantLifecycleOwner.onStop()
        stopListening()
        tts?.stop()
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy called")
        assistantLifecycleOwner.onDestroy()
        stopListening()
        tts?.shutdown()
        super.onDestroy()
    }

    private fun startSpeechRecognition() {
        Log.i(TAG, "startSpeechRecognition entry")
        try {
            val available = SpeechRecognizer.isRecognitionAvailable(context)
            Log.i(TAG, "SpeechRecognizer.isRecognitionAvailable: $available")
            if (!available) {
                statusState.value = "Speech recognition not available"
                Toast.makeText(context, "Speech Recognition is not available on this device.", Toast.LENGTH_LONG).show()
                return
            }

            stopListening()

            val googleService = ComponentName.unflattenFromString(
                "com.google.android.tts/com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService"
            )
            Log.i(TAG, "Creating SpeechRecognizer with component: $googleService")
            speechRecognizer = if (googleService != null) {
                SpeechRecognizer.createSpeechRecognizer(context.applicationContext, googleService)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
            }
            speechRecognizer?.apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        Log.i(TAG, "onReadyForSpeech")
                        statusState.value = "Listening..."
                        isListeningState.value = true
                    }

                    override fun onBeginningOfSpeech() {
                        statusState.value = "Recording..."
                    }

                    override fun onRmsChanged(rmsdB: Float) {}

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        statusState.value = "Processing..."
                        isListeningState.value = false
                    }

                    override fun onError(error: Int) {
                        isListeningState.value = false
                        val errorMsg = when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                            SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                            SpeechRecognizer.ERROR_NETWORK -> "Network error"
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                            SpeechRecognizer.ERROR_NO_MATCH -> "No match found"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer busy"
                            SpeechRecognizer.ERROR_SERVER -> "Server error"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
                            else -> "Unknown error"
                        }
                        statusState.value = "Speech recognizer: $errorMsg"
                        Log.w(TAG, "SpeechRecognizer Error: $errorMsg ($error)")
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            val resultText = matches[0]
                            statusState.value = "Thinking..."
                            
                            // Send query to python agent
                            sendQueryToAgent(resultText)
                        } else {
                            statusState.value = "Ready"
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            // Update input text field dynamically as user speaks
                            inputTextState.value = matches[0]
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                setPackage("com.google.android.tts")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                // Keep listening longer to prevent early cutoff
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            }
            Log.i(TAG, "Calling speechRecognizer?.startListening(intent)")
            speechRecognizer?.startListening(intent)

        } catch (e: Exception) {
            statusState.value = "Failed to start recognition"
            Log.e(TAG, "Recognition start failed: ${e.message}")
        }
    }

    private fun stopListening() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        isListeningState.value = false
    }

    private fun sendQueryToAgent(prompt: String) {
        // Add prompt immediately to list of messages
        handler.post {
            messages.add(ChatMessage(prompt, isUser = true))
        }

        executor.execute {
            var isQueryRunning = true
            val kaliStatusFile = RootfsManager.distroRootfsDir(context, "kali").resolve("tmp/nethunter_agent_status.json")
            val parrotStatusFile = RootfsManager.distroRootfsDir(context, "parrot").resolve("tmp/nethunter_agent_status.json")

            // Delete old files if they exist
            try { kaliStatusFile.delete() } catch (e: Exception) {}
            try { parrotStatusFile.delete() } catch (e: Exception) {}

            // Set initial status
            handler.post { statusState.value = "Connecting to agent..." }

            val statusPoller = Runnable {
                Log.d(TAG, "Status poller started.")
                while (isQueryRunning) {
                    try {
                        // Priority 1: Read in-memory status from LocalApiServer (reliable)
                        val serverStatus = LocalApiServer.currentAgentStatus
                        if (serverStatus.isNotEmpty()) {
                            handler.post { statusState.value = serverStatus }
                        } else {
                            // Priority 2: Read file-based status from chroot (bonus detail)
                            val statusFile = if (kaliStatusFile.exists()) kaliStatusFile
                                else if (parrotStatusFile.exists()) parrotStatusFile
                                else null
                            if (statusFile != null) {
                                val content = statusFile.readText()
                                if (content.isNotEmpty()) {
                                    val json = JSONObject(content)
                                    val currentStatus = json.optString("status", "")
                                    if (currentStatus.isNotEmpty()) {
                                        handler.post { statusState.value = currentStatus }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error polling status: ${e.message}")
                    }
                    try { Thread.sleep(250) } catch (e: Exception) {}
                }
                Log.d(TAG, "Status poller stopped.")
            }
            val pollerThread = Thread(statusPoller)
            pollerThread.start()

            try {
                val url = URL("http://127.0.0.1:1337/agent/query")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 5000
                conn.readTimeout = 0
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")

                val payload = JSONObject().put("prompt", prompt).toString()
                conn.outputStream.use { os ->
                    os.write(payload.toByteArray(Charsets.UTF_8))
                }

                if (conn.responseCode == 200) {
                    val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(responseText)
                    val reply = json.optString("response", "")
                    
                    handler.post {
                        statusState.value = "Ready"
                        messages.add(ChatMessage(reply, isUser = false))
                        speakResponse(reply)
                    }
                } else {
                    handler.post {
                        statusState.value = "Error"
                        val errorText = "Sorry, there was an error communicating with the agent."
                        messages.add(ChatMessage(errorText, isUser = false))
                        speakResponse(errorText)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error querying agent: ${e.message}")
                handler.post {
                    statusState.value = "Offline"
                    val errorText = "Sorry, I could not reach the Hunter agent server. Please make sure the daemon is running."
                    messages.add(ChatMessage(errorText, isUser = false))
                    speakResponse(errorText)
                }
            } finally {
                isQueryRunning = false
                try { pollerThread.join(1000) } catch (e: Exception) {}
                try { kaliStatusFile.delete() } catch (e: Exception) {}
                try { parrotStatusFile.delete() } catch (e: Exception) {}
            }
        }
    }

    private fun speakResponse(text: String) {
        if (ttsReady && tts != null) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nethunter_assistant_speech")
        } else {
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun AssistantOverlayLayout() {
        val status by statusState
        val isListening by isListeningState
        var inputText by inputTextState
        val listState = rememberLazyListState()

        // Auto scroll to bottom when messages list size changes
        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) {
                listState.animateScrollToItem(messages.size - 1)
            }
        }

        // Micro-animations for pulsing glowing indicator when listening
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val scale by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = if (isListening) 1.25f else 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(800, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "scale"
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Transparent),
            contentAlignment = Alignment.BottomCenter
        ) {
            // Invisible background click handler to close assistant
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Transparent)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        finish()
                    }
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
                    .heightIn(max = 450.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = true
                    ) {
                        // Dummy click to block click propagation to parent background dismisser
                    },
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, Color(0xFF00FF41)),
                colors = CardDefaults.cardColors(containerColor = Color(0xEE0C0E14))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Header Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Small pulsing LED indicator
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .scale(scale)
                                    .background(
                                        if (isListening) Color(0xFF00FF41) else Color(0xFF555555),
                                        CircleShape
                                    )
                            )
                            Text(
                                text = "[ STATUS: ${status.uppercase()} ]",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF00FF41),
                                letterSpacing = 1.sp
                            )
                        }

                        // Close Button
                        IconButton(
                            onClick = { finish() },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Text(
                                text = "✕",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF888888)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Scrollable Chat Message History
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(Color(0x33000000), RoundedCornerShape(8.dp))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(messages) { msg ->
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = if (msg.isUser) Alignment.End else Alignment.Start
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(
                                            RoundedCornerShape(
                                                topStart = 12.dp,
                                                topEnd = 12.dp,
                                                bottomStart = if (msg.isUser) 12.dp else 2.dp,
                                                bottomEnd = if (msg.isUser) 2.dp else 12.dp
                                            )
                                        )
                                        .background(
                                            if (msg.isUser) {
                                                Brush.horizontalGradient(
                                                    colors = listOf(Color(0xAA00FF41), Color(0xAA00AA2B))
                                                )
                                            } else {
                                                Brush.horizontalGradient(
                                                    colors = listOf(Color(0xFF161A22), Color(0xFF1E2430))
                                                )
                                            }
                                        )
                                        .border(
                                            0.5.dp,
                                            if (msg.isUser) Color(0xFF00FF41) else Color(0x33FFFFFF),
                                            RoundedCornerShape(
                                                topStart = 12.dp,
                                                topEnd = 12.dp,
                                                bottomStart = if (msg.isUser) 12.dp else 2.dp,
                                                bottomEnd = if (msg.isUser) 2.dp else 12.dp
                                            )
                                        )
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        text = msg.text,
                                        color = if (msg.isUser) Color.Black else Color(0xFFECEFF4),
                                        fontSize = 14.sp,
                                        fontFamily = if (msg.isUser) FontFamily.Default else FontFamily.Monospace,
                                        fontWeight = if (msg.isUser) FontWeight.Medium else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Bottom Input Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Speech/Mic Action
                        IconButton(
                            onClick = {
                                if (isListening) {
                                    stopListening()
                                    statusState.value = "Ready"
                                } else {
                                    startSpeechRecognition()
                                }
                            },
                            modifier = Modifier
                                .size(40.dp)
                                .background(
                                    if (isListening) Color(0xFF00FF41) else Color(0x33FFFFFF),
                                    CircleShape
                                )
                        ) {
                            Text(
                                text = if (isListening) "🎙" else "🎤",
                                fontSize = 18.sp
                            )
                        }

                        // Text Input Bar
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp),
                            placeholder = {
                                Text(
                                    "Type a message...",
                                    color = Color.Gray,
                                    fontSize = 14.sp
                                )
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                cursorColor = Color(0xFF00FF41),
                                focusedBorderColor = Color(0xFF00FF41),
                                unfocusedBorderColor = Color(0x44FFFFFF),
                                focusedContainerColor = Color(0xFF101216),
                                unfocusedContainerColor = Color(0xFF101216)
                            ),
                            shape = RoundedCornerShape(26.dp),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                imeAction = ImeAction.Send
                            ),
                            keyboardActions = KeyboardActions(
                                onSend = {
                                    if (inputText.trim().isNotEmpty()) {
                                        sendQueryToAgent(inputText.trim())
                                        inputText = ""
                                    }
                                }
                            )
                        )

                        // Send Button
                        IconButton(
                            onClick = {
                                if (inputText.trim().isNotEmpty()) {
                                    sendQueryToAgent(inputText.trim())
                                    inputText = ""
                                }
                            },
                            modifier = Modifier
                                .size(40.dp)
                                .background(Color(0xFF00FF41), CircleShape),
                            enabled = inputText.trim().isNotEmpty()
                        ) {
                            Text(
                                text = "➤",
                                color = Color.Black,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

