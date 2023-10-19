package ee.ria.taraauthserver.session;

public enum TaraAuthenticationState {
    INIT_AUTH_PROCESS,
    AUTHENTICATION_SUCCESS,
    AUTHENTICATION_CANCELED,
    WEBAUTHN_AUTHENTICATION_CANCELED,
    WEBAUTHN_REGISTRATION_CANCELED,
    WEBAUTHN_AUTHENTICATION_COMPLETED,
    WEBAUTHN_AUTHENTICATION_SUCCESS,
    VERIFICATION_COMPLETED,
    VERIFICATION_SUCCESS,
    VERIFICATION_CANCELED,
    VERIFICATION_FAILED,
    AUTHENTICATION_FAILED,
    INIT_MID,
    INIT_SID,
    INIT_VERIFF,
    POLL_MID_STATUS,
    POLL_MID_STATUS_CANCELED,
    POLL_SID_STATUS,
    POLL_SID_STATUS_CANCELED,
    POLL_VERIFF_STATUS,
    COMPLETE,
    NATURAL_PERSON_AUTHENTICATION_COMPLETED,
    LEGAL_PERSON_AUTHENTICATION_INIT,
    GET_LEGAL_PERSON_LIST,
    LEGAL_PERSON_AUTHENTICATION_COMPLETED,
    NATURAL_PERSON_AUTHENTICATION_CHECK_ESTEID_CERT,
    CONSENT_NOT_REQUIRED,
    INIT_CONSENT_PROCESS,
    CONSENT_GIVEN,
    CONSENT_NOT_GIVEN,
    WAITING_EIDAS_RESPONSE,
    EXTERNAL_TRANSACTION,
    WAITING_WEBAUTHN_RESPONSE,
    WAITING_VERIFF_RESPONSE
}
