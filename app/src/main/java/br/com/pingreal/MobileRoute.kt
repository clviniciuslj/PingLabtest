package br.com.pingreal

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

enum class RouteChoice {
    /** A rede padrão do sistema tem internet de verdade: usa ela e não gasta dados móveis. */
    DEFAULT,

    /** A rede padrão está sem internet (por exemplo, o Wi-Fi do cliente com a fibra fora): usa os dados móveis. */
    MOBILE,

    /** Sem internet na rede atual e sem dados móveis disponíveis. */
    NO_INTERNET
}

/** Por onde uma chamada de rede do chat vai sair. [release] devolve ao sistema os dados móveis pedidos. */
class Route(val network: Network?, val choice: RouteChoice, val release: () -> Unit = {})

/**
 * Faz o chat conseguir falar com o Gemini mesmo quando o Wi-Fi em que o celular está conectado não tem internet,
 * que é justamente quando o diagnóstico mais importa. Só o chat usa isso: o resto do app segue na rede padrão.
 */
object MobileRoute {
    const val WAIT_MS = 10_000L

    /** A regra: rede padrão com internet vence; senão, os dados móveis; senão, avisa que não há saída. */
    fun choose(defaultHasInternet: Boolean, mobileAcquired: Boolean): RouteChoice = when {
        defaultHasInternet -> RouteChoice.DEFAULT
        mobileAcquired -> RouteChoice.MOBILE
        else -> RouteChoice.NO_INTERNET
    }

    /** Escolhe a saída. Bloqueia por até [WAIT_MS] esperando os dados móveis: chame fora da tela. */
    fun acquire(context: Context): Route {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        // "Validated" é o Android confirmando que a rede realmente chega na internet (não só que está conectada).
        val defaultHasInternet = caps != null &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        if (defaultHasInternet) return Route(null, choose(true, false))

        val found = AtomicReference<Network?>(null)
        val latch = CountDownLatch(1)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                found.set(network)
                latch.countDown()
            }

            override fun onUnavailable() {
                latch.countDown()
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            cm.requestNetwork(request, callback, WAIT_MS.toInt())
            latch.await(WAIT_MS + 1_000, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            // sem rádio móvel, sem chip ou sem permissão: cai em "sem saída" abaixo
        }
        val release = {
            try {
                cm.unregisterNetworkCallback(callback)
            } catch (_: Exception) {
            }
        }
        val network = found.get()
        return if (network != null) {
            Route(network, choose(false, true), release)
        } else {
            release()
            Route(null, choose(false, false))
        }
    }
}
