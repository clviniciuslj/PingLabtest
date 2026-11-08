package br.com.pingreal

import android.graphics.Color

/**
 * Cores do app, tiradas do mundo da fibra: amarelo do cabo monomodo (marca), verde do conector APC (ok),
 * vermelho da caneta de luz VFL (falha), laranja (atenção) e azul do conector UPC.
 * Duas paletas, "noite" e "dia"; [apply] escolhe qual vale e deve ser chamada antes de montar as telas.
 */
object Palette {
    var isDark = true
        private set

    /** Fundo da tela. */
    var bg = 0; private set
    /** Superfície de cartões e campos. */
    var panel = 0; private set
    /** Superfície um nível acima (chips, pílula ativa). */
    var panelHigh = 0; private set
    /** Linha fina de contorno e divisórias. */
    var panelStroke = 0; private set
    /** Fundo do console. */
    var consoleBg = 0; private set
    /** Amarelo da marca (preenchimentos). */
    var accent = 0; private set
    /** Texto sobre o amarelo. */
    var onAccent = 0; private set
    /** Amarelo legível como texto/ícone sobre o fundo. */
    var accentInk = 0; private set
    var green = 0; private set
    var cyan = 0; private set
    var violet = 0; private set
    var orange = 0; private set
    var amber = 0; private set
    var red = 0; private set
    /** Texto forte (títulos e valores). */
    var white = 0; private set
    /** Texto comum. */
    var text = 0; private set
    /** Texto secundário. */
    var muted = 0; private set

    /** Cores das curvas quando há vários alvos. */
    var series = IntArray(4); private set

    /** Branco translúcido na noite, preto translúcido no dia (pressionado, trilhas, grades). */
    fun overlay(alpha: Int): Int =
        if (isDark) Color.argb(alpha, 255, 255, 255) else Color.argb(alpha, 0, 0, 0)

    init { apply(true) }

    fun apply(dark: Boolean) {
        isDark = dark
        if (dark) {
            // Medidas no vídeo do WiFiman: grafite neutro com um azul só de destaque.
            bg = Color.rgb(17, 18, 21)
            panel = Color.rgb(28, 29, 34)
            panelHigh = Color.rgb(40, 41, 47)
            panelStroke = Color.rgb(44, 45, 51)
            consoleBg = Color.rgb(20, 21, 24)
            accent = Color.rgb(72, 150, 255)
            onAccent = Color.rgb(250, 251, 255)
            accentInk = Color.rgb(72, 150, 255)
            green = Color.rgb(55, 188, 94)
            cyan = Color.rgb(50, 200, 220)
            violet = Color.rgb(176, 140, 255)
            orange = Color.rgb(228, 149, 17)
            amber = Color.rgb(228, 149, 17)
            red = Color.rgb(255, 92, 108)
            white = Color.rgb(250, 251, 255)
            text = Color.rgb(216, 217, 222)
            muted = Color.rgb(184, 185, 190)
        } else {
            bg = Color.rgb(242, 242, 247)
            panel = Color.WHITE
            panelHigh = Color.rgb(246, 246, 250)
            panelStroke = Color.rgb(226, 226, 232)
            consoleBg = Color.rgb(247, 247, 250)
            accent = Color.rgb(36, 124, 250)
            onAccent = Color.WHITE
            accentInk = Color.rgb(0, 100, 220)
            green = Color.rgb(24, 148, 68)
            cyan = Color.rgb(0, 140, 170)
            violet = Color.rgb(120, 70, 210)
            orange = Color.rgb(200, 112, 0)
            amber = Color.rgb(200, 112, 0)
            red = Color.rgb(212, 42, 62)
            white = Color.rgb(17, 18, 21)
            text = Color.rgb(58, 60, 68)
            muted = Color.rgb(110, 113, 122)
        }
        series = intArrayOf(accentInk, cyan, violet, green)
    }
}
