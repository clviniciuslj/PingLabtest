package br.com.pingreal

import android.content.SharedPreferences

/** O que as abas pedem à activity: ocupar o app (um teste por vez), rede atual, compartilhar etc. */
interface PageHost {
    /** Marca o app como ocupado pela aba que chamou. Devolve false se outra coisa já está rodando. */
    fun acquire(label: String, color: Int): Boolean
    fun release()

    fun netInfo(): NetInfo
    fun prefs(): SharedPreferences
    fun post(block: () -> Unit)
    fun setKeepScreenOn(on: Boolean)

    /** Abre o seletor de arquivos e entrega o texto do arquivo escolhido (CSV/TXT). */
    fun pickTextFile(onText: (String) -> Unit)

    fun shareText(text: String)
    fun copyText(text: String)

    /** Gera e compartilha uma imagem com título, data, o gráfico (se houver) e o texto. */
    fun shareImage(title: String, body: String, chart: LatencyChartView?)
}
