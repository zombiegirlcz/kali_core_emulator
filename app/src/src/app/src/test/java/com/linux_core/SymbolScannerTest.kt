package com.linux_core

import org.junit.Test
import java.io.File

class SymbolScannerTest {
    @Test
    fun scanSymbols() {
        val soFile = File("src/main/jniLibs/arm64-v8a/libadguard-core.so")
        val text = String(soFile.readBytes(), Charsets.US_ASCII)
        
        val lines = mutableListOf<String>()
        val words = text.split(Regex("[^a-zA-Z0-9_]+"))
        for (i in words.indices) {
            if (words[i] == "callbacks") {
                lines.add(words[i])
            }
        }
        File("build/symbols.txt").writeText(lines.distinct().joinToString("\n"))
    }
}