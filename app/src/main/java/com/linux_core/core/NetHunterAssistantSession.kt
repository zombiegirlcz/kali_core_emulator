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
    private var statusState = mutableStateOf("Initializing...")
    private var speechTextState = mutableStateOf("")
    private var isListeningState = mutableStateOf(false)

    override fun onCreate() {
        super.onCreate()
        
        // Window settings for assistant overlay
        val dialog = window ?: return
        val win = dialog.window ?: return
        val lp = win.attributes
        lp.gravity = Gravity.BOTTOM or Gravity.FILL_HORIZONTAL
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        win.attributes = lp

        // Initialize TTS
        tts = TextToSpeech(context, this)
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
                        statusState.value = "Error: $errorMsg"
                        Log.w(TAG, "SpeechRecognizer Error: $errorMsg ($error)")
                        
                        // Close assistant after short delay on error
                        handler.postDelayed({ finish() }, 2000)
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            val resultText = matches[0]
                            speechTextState.value = resultText
                            statusState.value = "Thinking..."
                            
                            // Send query to python agent
                            sendQueryToAgent(resultText)
                        } else {
                            statusState.value = "No speech detected"
                            handler.postDelayed({ finish() }, 1500)
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            speechTextState.value = matches[0]
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
        executor.execute {
            var isQueryRunning = true
            val kaliStatusFile = java.io.File(context.filesDir, "kali-arm64/tmp/nethunter_agent_status.json")
            val parrotStatusFile = java.io.File(context.filesDir, "parrot-arm64/tmp/nethunter_agent_status.json")

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
                        statusState.value = "Speaking..."
                        speakResponse(reply)
                    }
                } else {
                    handler.post {
                        statusState.value = "Agent error"
                        speakResponse("Sorry, there was an error communicating with the agent.")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error querying agent: ${e.message}")
                handler.post {
                    statusState.value = "Agent offline"
                    speakResponse("Sorry, I could not reach the Hunter agent server. Please make sure the daemon is running.")
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
            
            // Periodically check if TTS is still speaking, then finish session
            val checkSpeaking = object : Runnable {
                override fun run() {
                    if (tts?.isSpeaking == true) {
                        handler.postDelayed(this, 500)
                    } else {
                        finish() // Close overlay when done speaking
                    }
                }
            }
            handler.postDelayed(checkSpeaking, 1000)
        } else {
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    @Composable
    fun AssistantOverlayLayout() {
        val status by statusState
        val speechText by speechTextState
        val isListening by isListeningState

        // Micro-animations for pulsing glowing indicator
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

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, Color(0xFF00FF41)),
            colors = CardDefaults.cardColors(containerColor = Color(0xEE0C0E14))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Glow line header
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.2f)
                        .height(3.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xFF00FF41))
                )
                
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Pulsing Microphone indicator
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .scale(scale)
                            .border(1.5.dp, Color(0xFF00FF41), CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(Color(0x3300FF41), Color.Transparent)
                                ),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .background(Color(0xFF00FF41), CircleShape)
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = status.uppercase(),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00FF41),
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (speechText.isEmpty()) "Say something..." else speechText,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (speechText.isEmpty()) Color.DarkGray else Color.White
                        )
                    }
                }
            }
        }
    }
}
