package dev.locus.app;

import android.app.Application;

public final class LocusApplication extends Application {
    private WorkspaceSession workspace;
    // Accessed by the UI and service on the main thread.
    WorkspaceService foregroundWorkspace;
    boolean foregroundWorkspaceRequested;

    WorkspaceSession workspace() {
        if (workspace == null) workspace = new WorkspaceSession(this);
        return workspace;
    }
}
