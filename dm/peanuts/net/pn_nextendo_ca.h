// PEANUTS: Nextendo Network's root certificate authority, for the hosts the DNS redirect sends to Nextendo (pn_trust.c). Public data: the certificate Nextendo publishes
// for its clients to trust, romfs/sd/rootCA.pem of NextendoNetwork/Prelude-Nextendo (https://git.nextendo.network/NextendoNetwork/Prelude-Nextendo), as of commit
// ba4af9fa8f0558b45bad047bedd07ff879c6ce4e; that file's SHA-256 is f718064f3cea82eb96983da934a61b7757b64730ff0466c046bb47cd574842fc.
// CN=Nextendo Network CA, O=Nextendo; valid 2026-06-30 to 2036-06-27; subject key identifier 0C:CF:CB:8A:7B:ED:72:42:80:12:64:41:76:19:D5:64:32:EB:71:47.
#ifndef PN_NEXTENDO_CA_H
#define PN_NEXTENDO_CA_H
static const char pn_nextendo_ca_pem[] =
    "-----BEGIN CERTIFICATE-----\n"
    "MIIDUzCCAjugAwIBAgIUe2MLLv0r9SUfUu/Y3QCzveSq6eswDQYJKoZIhvcNAQEL\n"
    "BQAwMTEcMBoGA1UEAwwTTmV4dGVuZG8gTmV0d29yayBDQTERMA8GA1UECgwITmV4\n"
    "dGVuZG8wHhcNMjYwNjMwMTk0OTM0WhcNMzYwNjI3MTk0OTM0WjAxMRwwGgYDVQQD\n"
    "DBNOZXh0ZW5kbyBOZXR3b3JrIENBMREwDwYDVQQKDAhOZXh0ZW5kbzCCASIwDQYJ\n"
    "KoZIhvcNAQEBBQADggEPADCCAQoCggEBAMJWnrQ4xsG7V+y0vmzqT9NVLf8yJhbQ\n"
    "gpCEYleo2rV6IwCB499spgdzet7pOV64TSa67maF+wqBSfBJ2k0Vy3ceQCTJAFs3\n"
    "orpWE6GcCIGoj18CSYQgeCm8ih4rNRECTYo/cKlDKeeZ+jL+JKSYc1HngZ78ORie\n"
    "YZMJ0L2liWrbvoKvvYJ0tbOm7Cth9GWcS3IKGv1IB0Kc+qDAa2mVZ6fv7N2DJEGh\n"
    "OMgqTebxrkI04VQk5E35fgO83dgrH/nj1Wt4oYRTOJtj5bQCLpKztggmUxYiTNUo\n"
    "aawGWRKH4nmeBwR7sVM8SVkk77j/FSYFC5ZJRsjgHAmiAAv0lpCBM0sCAwEAAaNj\n"
    "MGEwHQYDVR0OBBYEFAzPy4p77XJCgBJkQXYZ1WQy63FHMB8GA1UdIwQYMBaAFAzP\n"
    "y4p77XJCgBJkQXYZ1WQy63FHMA8GA1UdEwEB/wQFMAMBAf8wDgYDVR0PAQH/BAQD\n"
    "AgEGMA0GCSqGSIb3DQEBCwUAA4IBAQCKt6g4McjcBCEGysAGf0kREMdA/mpPaaId\n"
    "NGACL82AnPP7yeS6lFpAhYTB0nYik2etVGyzSym3WumPNCedIcT/9HmvzBdoXXIi\n"
    "lIzzg3kndbkCrYQgoM6yeV6pPoJY5Xe0iISEUsMZdSJo2CTpL2pnoD4DVmmtz/iK\n"
    "j/x/8+UbhQmAiVEEgfZAqgiwZ79E1639QKtpeWiX7iDopC+mZJtcmDIak/SX4i3k\n"
    "xiEuumNCYO+o9cKzFLUqsbxMHdAE6FIBrurc8grhOawAqpRG/gJgc1uZkmkg4tez\n"
    "ERdWDQSMRaS1/hySxRaKZxh2scmfDlpexIo0hgO5FTxb5tDylvMt\n"
    "-----END CERTIFICATE-----\n"
;
#endif
