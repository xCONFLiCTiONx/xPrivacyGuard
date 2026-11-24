#pragma once
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* All JSON-returning functions return owned UTF-8 strings.
   Release every non-null returned string with aether_string_free(). */
char *aether_version(void);
void aether_string_free(char *value);
char *aether_job_poll(uint64_t job);
char *aether_job_cancel(uint64_t job);
char *aether_job_free(uint64_t job);

/* Exported by the xPrivacyVPN raw-IP MASQUE FFI patch.
   The upstream Aether release does not export these until that patch is rebased. */
char *aether_raw_tunnel_start(uint64_t identity, const char *json_payload);
int32_t aether_raw_tunnel_send(uint64_t tunnel, const uint8_t *packet, size_t length);
int32_t aether_raw_tunnel_recv(uint64_t tunnel, uint8_t *buffer, size_t capacity, uint32_t timeout_ms);
int32_t aether_raw_tunnel_is_alive(uint64_t tunnel);
int32_t aether_raw_tunnel_migrate(uint64_t tunnel);
char *aether_raw_tunnel_stop(uint64_t tunnel);

#ifdef __cplusplus
}
#endif
