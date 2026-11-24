package dev.locus.app;

import java.io.IOException;

/** A recoverable operation failure with a stable reason for UI localization. */
class OperationException extends IOException {
    enum Reason {
        DOCUMENT_READ_FAILED,
        DOCUMENT_SIZE,
        SSH_KEY_REQUIRED,
        SSH_KEY_SIZE,
        SSH_KEY_INVALID,
        TERMINAL_CLOSED,
        REMOTE_NOT_DIRECTORY,
        REMOTE_NOT_FILE,
        REMOTE_PATH_INVALID,
        DIRECTORY_LIMIT,
        SSH_HOST_APPROVAL_REQUIRED,
        SSH_HOST_CHANGED,
        SSH_HOST_KEY_UNREADABLE,
        SSH_HOST_IDENTITY_MISMATCH,
        SSH_HOST_VERIFICATION_FAILED,
        SSH_OPERATION_FAILED,
        SFTP_NOT_FOUND,
        SFTP_PERMISSION_DENIED,
        SFTP_UNSUPPORTED,
        SFTP_DISCONNECTED,
        CREDENTIAL_READ_FAILED,
        CREDENTIAL_SIZE,
        CREDENTIAL_SAVE_FAILED,
        CREDENTIAL_REMOVE_FAILED,
        SAVED_CONNECTION_READ_FAILED,
        SAVED_CONNECTION_WRITE_FAILED,
        SAVED_LOGIN_MISMATCH,
        OPENSSH_CONFIG_SIZE,
        OPENSSH_CONFIG_ENCODING,
        OPENSSH_CONFIG_NO_ALIASES,
        OPENSSH_CONFIG_SYNTAX,
        FRP_PROFILE_REQUIRED,
        FRP_PROFILE_SIZE,
        FRP_PROFILE_INVALID,
        FRP_PROFILE_NEEDS_VISITOR,
        FRP_PROFILE_UNSUPPORTED,
        FRP_PROFILE_SELECTION_FAILED,
        FRP_RELAY_START_FAILED,
        FRP_RELAY_TIMEOUT,
        FRP_RELAY_LOGIN_FAILED
    }

    final Reason reason;
    // DOCUMENT_SIZE carries KiB; OPENSSH_CONFIG_SYNTAX carries a one-based line number.
    final int detail;

    OperationException(Reason reason, String message) {
        this(reason, 0, message, null);
    }

    OperationException(Reason reason, String message, Throwable cause) {
        this(reason, 0, message, cause);
    }

    OperationException(Reason reason, int detail, String message) {
        this(reason, detail, message, null);
    }

    private OperationException(Reason reason, int detail, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.detail = detail;
    }
}
