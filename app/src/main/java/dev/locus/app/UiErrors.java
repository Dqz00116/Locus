package dev.locus.app;

import android.content.Context;

import com.jcraft.jsch.JSchAlgoNegoFailException;
import com.jcraft.jsch.JSchException;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;

/** Converts operation results to display text without showing implementation exceptions. */
final class UiErrors {
    private UiErrors() {}

    static String message(Context context, Exception failure) {
        if (failure instanceof OperationException) {
            OperationException operation = (OperationException) failure;
            int resource = resource(operation.reason);
            switch (operation.reason) {
                case DOCUMENT_SIZE:
                case OPENSSH_CONFIG_SYNTAX:
                    return context.getString(resource, operation.detail);
                default:
                    return context.getString(resource);
            }
        }
        if (failure instanceof JSchAlgoNegoFailException) {
            return context.getString(R.string.error_ssh_algorithms);
        }
        if (isAuthenticationFailure(failure)) {
            return context.getString(R.string.error_ssh_auth_rejected);
        }
        if (FrpRoute.isSshTimeout(failure)) {
            return context.getString(R.string.error_ssh_timeout);
        }
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof UnknownHostException) {
                return context.getString(R.string.error_server_address);
            }
            if (cause instanceof ConnectException || cause instanceof NoRouteToHostException) {
                return context.getString(R.string.error_server_unreachable);
            }
        }
        return context.getString(R.string.error_operation);
    }

    static boolean isAuthenticationFailure(Exception failure) {
        // JSch does not expose a distinct exception type for authentication rejection.
        if (!(failure instanceof JSchException)) return false;
        String message = failure.getMessage();
        return message != null && message.startsWith("Auth fail");
    }

    private static int resource(OperationException.Reason reason) {
        switch (reason) {
            case DOCUMENT_READ_FAILED: return R.string.error_document_read;
            case DOCUMENT_SIZE: return R.string.error_document_size;
            case SSH_KEY_REQUIRED: return R.string.error_ssh_key_required;
            case SSH_KEY_SIZE: return R.string.error_ssh_key_size;
            case SSH_KEY_INVALID: return R.string.error_ssh_key_invalid;
            case TERMINAL_CLOSED: return R.string.error_terminal_closed;
            case REMOTE_NOT_DIRECTORY: return R.string.error_remote_not_directory;
            case REMOTE_NOT_FILE: return R.string.error_remote_not_file;
            case REMOTE_PATH_INVALID: return R.string.error_remote_path_invalid;
            case DIRECTORY_LIMIT: return R.string.error_directory_limit;
            case SSH_HOST_APPROVAL_REQUIRED: return R.string.error_ssh_host_approval;
            case SSH_HOST_CHANGED: return R.string.error_ssh_host_changed;
            case SSH_HOST_KEY_UNREADABLE: return R.string.error_ssh_host_key_unreadable;
            case SSH_HOST_IDENTITY_MISMATCH: return R.string.error_ssh_host_identity;
            case SSH_HOST_VERIFICATION_FAILED: return R.string.error_ssh_host_verification;
            case SSH_OPERATION_FAILED: return R.string.error_ssh_operation;
            case SFTP_NOT_FOUND: return R.string.error_sftp_not_found;
            case SFTP_PERMISSION_DENIED: return R.string.error_sftp_permission;
            case SFTP_UNSUPPORTED: return R.string.error_sftp_unsupported;
            case SFTP_DISCONNECTED: return R.string.error_sftp_disconnected;
            case CREDENTIAL_READ_FAILED: return R.string.error_credential_read;
            case CREDENTIAL_SIZE: return R.string.error_credential_size;
            case CREDENTIAL_SAVE_FAILED: return R.string.error_credential_save;
            case CREDENTIAL_REMOVE_FAILED: return R.string.error_credential_remove;
            case SAVED_CONNECTION_READ_FAILED: return R.string.error_saved_connection_read;
            case SAVED_CONNECTION_WRITE_FAILED: return R.string.error_saved_connection_write;
            case SAVED_LOGIN_MISMATCH: return R.string.error_saved_login_mismatch;
            case OPENSSH_CONFIG_SIZE: return R.string.error_openssh_size;
            case OPENSSH_CONFIG_ENCODING: return R.string.error_openssh_encoding;
            case OPENSSH_CONFIG_NO_ALIASES: return R.string.error_openssh_no_aliases;
            case OPENSSH_CONFIG_SYNTAX: return R.string.error_openssh_syntax;
            case FRP_PROFILE_REQUIRED: return R.string.error_frp_required;
            case FRP_PROFILE_SIZE: return R.string.error_frp_size;
            case FRP_PROFILE_INVALID: return R.string.error_frp_invalid;
            case FRP_PROFILE_NEEDS_VISITOR: return R.string.error_frp_needs_visitor;
            case FRP_PROFILE_UNSUPPORTED: return R.string.error_frp_unsupported;
            case FRP_PROFILE_SELECTION_FAILED: return R.string.error_frp_select;
            case FRP_RELAY_START_FAILED: return R.string.error_frp_start;
            case FRP_RELAY_TIMEOUT: return R.string.error_frp_timeout;
            case FRP_RELAY_LOGIN_FAILED: return R.string.error_frp_login;
            default: return R.string.error_operation;
        }
    }
}
