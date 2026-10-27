package com.flipperclaude.bridge

object Protocol {
    const val MAX_CHUNK_BYTES = 120

    fun escape(text: String): String =
        text.replace("\\", "\\\\").replace("\r", "").replace("\n", "\\n")

    fun unescape(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                val n = text[i + 1]
                if (n == 'n') {
                    out.append('\n')
                    i += 2
                    continue
                }
                if (n == '\\') {
                    out.append('\\')
                    i += 2
                    continue
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    fun answerLines(text: String): List<String> {
        val lines = ArrayList<String>()
        val current = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            val piece = when (cp) {
                '\r'.code -> continue
                '\n'.code -> "\\n"
                '\\'.code -> "\\\\"
                else -> String(Character.toChars(cp))
            }
            val size = piece.toByteArray(Charsets.UTF_8).size
            if (bytes + size > MAX_CHUNK_BYTES) {
                lines.add("A:$current")
                current.setLength(0)
                bytes = 0
            }
            current.append(piece)
            bytes += size
        }
        if (current.isNotEmpty()) lines.add("A:$current")
        return lines
    }
}

class LineAssembler(private val onLine: (String) -> Unit) {
    private val buffer = java.io.ByteArrayOutputStream()

    @Synchronized
    fun feed(data: ByteArray) {
        for (b in data) {
            when (b) {
                '\n'.code.toByte() -> {
                    if (buffer.size() > 0) {
                        val line = buffer.toString(Charsets.UTF_8.name())
                        buffer.reset()
                        onLine(line)
                    }
                }
                '\r'.code.toByte() -> Unit
                else -> if (buffer.size() < 4096) buffer.write(b.toInt())
            }
        }
    }

    @Synchronized
    fun reset() = buffer.reset()
}

interface Transport {
    val label: String
    fun send(line: String)
}
