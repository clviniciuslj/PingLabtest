package br.com.pingreal

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

/** O markdown mínimo que o Gemini usa nas respostas: negrito, código, títulos e listas. Sem tabelas nem links. */
object MarkdownLite {

    class Run(val text: String, val bold: Boolean = false, val mono: Boolean = false)

    /** Quebra o texto em trechos com estilo. Os marcadores (`**`, `` ` ``, `#`, `* `) somem do resultado. */
    fun parse(markdown: String): List<Run> {
        val out = ArrayList<Run>()
        var inFence = false
        val lines = markdown.replace("\r\n", "\n").split("\n")
        for ((index, rawLine) in lines.withIndex()) {
            val line = rawLine.trimEnd()
            if (line.trimStart().startsWith("```")) {
                inFence = !inFence
                continue
            }
            if (inFence) {
                out.add(Run(line, mono = true))
            } else {
                val heading = Regex("^\\s{0,3}#{1,6}\\s+(.*)$").find(line)
                val bullet = Regex("^(\\s*)[*\\-+]\\s+(.*)$").find(line)
                when {
                    heading != null -> out.addAll(inline(heading.groupValues[1], forceBold = true))
                    bullet != null -> {
                        out.add(Run(bullet.groupValues[1] + "• "))
                        out.addAll(inline(bullet.groupValues[2]))
                    }
                    else -> out.addAll(inline(line))
                }
            }
            if (index < lines.lastIndex) out.add(Run("\n"))
        }
        return merge(out)
    }

    private fun inline(text: String, forceBold: Boolean = false): List<Run> {
        val out = ArrayList<Run>()
        var bold = forceBold
        var mono = false
        val buffer = StringBuilder()
        fun flush() {
            if (buffer.isNotEmpty()) {
                out.add(Run(buffer.toString(), bold, mono))
                buffer.clear()
            }
        }
        var i = 0
        while (i < text.length) {
            when {
                !mono && text.startsWith("**", i) -> {
                    flush()
                    bold = if (forceBold) true else !bold
                    i += 2
                }
                text[i] == '`' -> {
                    flush()
                    mono = !mono
                    i++
                }
                !mono && text[i] == '*' && !(isSpaceAt(text, i - 1) && isSpaceAt(text, i + 1)) -> i++ // *itálico* vira texto simples
                else -> {
                    buffer.append(text[i])
                    i++
                }
            }
        }
        flush()
        return out
    }

    /** Fora do texto conta como espaço: um `*` solto entre espaços (uma multiplicação, por exemplo) fica como está. */
    private fun isSpaceAt(text: String, index: Int) = index < 0 || index >= text.length || text[index].isWhitespace()

    private fun merge(runs: List<Run>): List<Run> {
        val out = ArrayList<Run>()
        for (r in runs) {
            val last = out.lastOrNull()
            if (last != null && last.bold == r.bold && last.mono == r.mono) out[out.lastIndex] = Run(last.text + r.text, r.bold, r.mono)
            else out.add(r)
        }
        return out
    }

    /** Converte o markdown em texto com estilo para um TextView. */
    fun render(markdown: String, codeBackground: Int): CharSequence {
        val sb = SpannableStringBuilder()
        for (run in parse(markdown)) {
            val start = sb.length
            sb.append(run.text)
            val end = sb.length
            if (end == start) continue
            if (run.bold) sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (run.mono) {
                sb.setSpan(TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(BackgroundColorSpan(codeBackground), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return sb
    }
}
