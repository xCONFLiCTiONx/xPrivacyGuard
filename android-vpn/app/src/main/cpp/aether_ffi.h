#pragma once

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

void aether_set_android_socket_protector(int (*callback)(int));
char *aether_version(void);
void aether_string_free(char *raw);
char *aether_job_poll(uint64_t id);
char *aether_job_cancel(uint64_t id);
char *aether_job_free(uint64_t id);
char *aether_identity_open(const char *payload);
char *aether_identity_summary(uint64_t id);
char *aether_identity_free(uint64_t id);
char *aether_scan_start(uint64_t identity, const char *payload);
char *aether_verify_start(uint64_t identity, const char *payload);
char *aether_tunnel_start(uint64_t identity, const char *payload);
char *aether_team_sign_in(const char *payload);
char *aether_team_code_request(const char *payload);
char *aether_team_code_resend(uint64_t session);
char *aether_team_code_submit(uint64_t session, const char *code);
char *aether_team_session_free(uint64_t session);
char *aether_team_token_set(const char *token);
char *aether_team_token_clear(void);
char *aether_raw_tunnel_start(uint64_t identity, const char *payload);
int32_t aether_raw_tunnel_send(uint64_t tunnel, const uint8_t *data, size_t len);
int32_t aether_raw_tunnel_recv(uint64_t tunnel, uint8_t *buf, size_t cap, uint32_t timeout_ms);
int32_t aether_raw_tunnel_is_alive(uint64_t tunnel);
int32_t aether_raw_tunnel_migrate(uint64_t tunnel);
char *aether_raw_tunnel_stop(uint64_t tunnel);

#ifdef __cplusplus
}
#endif
