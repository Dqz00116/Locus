package dev.locus.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;

import dev.locus.app.DiagnosticLog.Outcome;
import dev.locus.app.DiagnosticLog.Stage;

/** Observes Android's default network without inspecting its addresses or configuration. */
final class DiagnosticNetwork extends ConnectivityManager.NetworkCallback implements AutoCloseable {
    private final ConnectivityManager manager;
    private final DiagnosticLog diagnostics;
    private Outcome transport;
    private Outcome validation;
    private Outcome access;

    // Only the debug manifest declares this permission. Check it at runtime for both variants.
    @SuppressLint("MissingPermission")
    static DiagnosticNetwork start(Context context, Handler handler, DiagnosticLog diagnostics) {
        if (!diagnostics.isEnabled()) return null;
        if (context.checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE)
                != PackageManager.PERMISSION_GRANTED) return null;
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        if (manager == null) return null;
        DiagnosticNetwork observer = new DiagnosticNetwork(manager, diagnostics);
        try {
            manager.registerDefaultNetworkCallback(observer, handler);
            return observer;
        } catch (RuntimeException unavailable) {
            diagnostics.record(Stage.NETWORK, Outcome.FAILED);
            return null;
        }
    }

    private DiagnosticNetwork(ConnectivityManager manager, DiagnosticLog diagnostics) {
        this.manager = manager;
        this.diagnostics = diagnostics;
    }

    @Override
    public void onAvailable(Network network) {
        transport = null;
        validation = null;
        access = null;
        diagnostics.record(Stage.NETWORK, Outcome.AVAILABLE);
    }

    @Override
    public void onLost(Network network) {
        diagnostics.record(Stage.NETWORK, Outcome.LOST);
    }

    @Override
    public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
        Outcome nextTransport = transportOf(capabilities);
        if (nextTransport != transport) {
            transport = nextTransport;
            diagnostics.record(Stage.NETWORK, transport);
        }
        Outcome nextValidation = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                ? Outcome.VALIDATED : Outcome.UNVALIDATED;
        if (nextValidation != validation) {
            validation = nextValidation;
            diagnostics.record(Stage.NETWORK, validation);
        }
    }

    @Override
    public void onBlockedStatusChanged(Network network, boolean blocked) {
        Outcome nextAccess = blocked ? Outcome.BLOCKED : Outcome.UNBLOCKED;
        if (nextAccess != access) {
            access = nextAccess;
            diagnostics.record(Stage.NETWORK, access);
        }
    }

    private static Outcome transportOf(NetworkCapabilities capabilities) {
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return Outcome.VPN;
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return Outcome.WIFI;
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return Outcome.CELLULAR;
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return Outcome.ETHERNET;
        return Outcome.OTHER_NETWORK;
    }

    @Override
    public void close() {
        manager.unregisterNetworkCallback(this);
    }
}
