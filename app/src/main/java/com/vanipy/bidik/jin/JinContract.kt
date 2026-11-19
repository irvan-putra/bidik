package com.vanipy.bidik.jin

/**
 * Bidik-side mirror of the Jin cross-app transport contract v1
 * (jin repo, docs/transport-contract.md, branch transport-v1). These
 * constants must match Jin's TransportContract exactly; the joint
 * runtime test is what proves they do. Nothing here authorizes anything:
 * Jin authenticates every call by binder uid + signing-cert pin against
 * default-off owner grants, and all of these calls can fail with
 * NOT_AUTHORIZED until the owner grants Bidik in Jin's Connected-apps UI.
 */
object JinContract {
    const val VERSION = 1
    const val AUTHORITY = "com.vanipy.jin.transport"
    const val JIN_PACKAGE = "com.vanipy.jin"

    const val DEFAULT_MAX_BYTES = 20L * 1024 * 1024
    const val STAGE_TOKEN_TTL_MS = 10L * 60 * 1000

    const val M_CAPABILITIES = "capabilities"
    const val M_LIST_CHATS = "listChats"
    const val M_STAGE = "stage"
    const val M_COMMIT = "commit"
    const val M_CANCEL = "cancel"
    const val M_STATUS = "status"
    const val M_POLL = "poll"

    const val K_OK = "ok"
    const val K_ERROR = "error"
    const val K_CONTRACT_VERSION = "contract_version"
    const val K_MAX_BYTES = "max_bytes"
    const val K_GRANTED = "granted"
    const val K_PREFIX = "prefix"
    const val K_URI = "uri"
    const val K_REF = "ref"
    const val K_STATUS = "status"
    const val K_META = "meta"
    const val K_ITEMS = "items"
    const val K_CURSOR = "cursor"
    const val K_GAP = "gap"
    const val K_FIRST_AVAILABLE = "first_available"

    const val E_NOT_AUTHORIZED = "NOT_AUTHORIZED"
    const val E_BAD_TOKEN = "BAD_TOKEN"
    const val E_TOKEN_REUSED = "TOKEN_REUSED"
    const val E_FILE_TOO_LARGE = "FILE_TOO_LARGE"
    const val E_BOT_NOT_RUNNING = "BOT_NOT_RUNNING"
    const val E_MALFORMED = "MALFORMED"
    const val E_UNKNOWN_METHOD = "UNKNOWN_METHOD"
    const val E_FUTURE_TS = "FUTURE_TIMESTAMP"
    const val E_CHAT_NOT_GRANTED = "CHAT_NOT_GRANTED"
    const val E_STAGE_INCOMPLETE = "STAGE_INCOMPLETE"

    const val S_QUEUED = "QUEUED"
    const val S_SENT_TO_RELAY = "SENT_TO_RELAY"
    const val S_FAILED = "FAILED"
    const val S_EXPIRED = "EXPIRED"
    const val S_READ = "READ"

    val TOKEN_REGEX = Regex("[A-Za-z0-9_\\-]{8,64}")

    fun stageUri(token: String) = "content://$AUTHORITY/stage/$token"
}
