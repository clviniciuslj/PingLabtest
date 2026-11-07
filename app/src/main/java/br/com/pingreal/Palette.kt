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
            bg = Color.rgb(13, 20, 32)
            panel = Color.rgb(20, 29, 43)
            panelHigh = Color.rgb(28, 39, 57)
            panelStroke = Color.rgb(34, 48, 74)
            consoleBg = Color.rgb(9, 14, 23)
            accent = Color.rgb(255, 201, 60)
            onAccent = Color.rgb(26, 20, 5)
            accentInk = Color.rgb(255, 201, 60)
            green = Color.rgb(61, 214, 140)
            cyan = Color.rgb(84, 166, 255)
            violet = Color.rgb(196, 140, 255)
            orange = Color.rgb(255, 145, 77)
            amber = Color.rgb(255, 145, 77)
            red = Color.rgb(255, 92, 108)
            white = Color.rgb(237, 241, 247)
            text = Color.rgb(196, 205, 219)
            muted = Color.rgb(132, 146, 168)
        } else {
            bg = Color.rgb(238, 241, 245)
            panel = Color.WHITE
            panelHigh = Color.rgb(244, 246, 249)
            panelStroke = Color.rgb(220, 226, 234)
            consoleBg = Color.rgb(247, 249, 252)
            accent = Color.rgb(255, 196, 38)
            onAccent = Color.rgb(26, 20, 5)
            accentInk = Color.rgb(143, 100, 0)
            green = Color.rgb(14, 150, 86)
            cyan = Color.rgb(22, 108, 214)
            violet = Color.rgb(132, 72, 214)
            orange = Color.rgb(214, 96, 16)
            amber = Color.rgb(214, 96, 16)
            red = Color.rgb(212, 42, 62)
            white = Color.rgb(16, 24, 38)
            text = Color.rgb(52, 64, 84)
            muted = Color.rgb(102, 115, 136)
        }
        series = intArrayOf(accentInk, cyan, violet, green)
    }
}
