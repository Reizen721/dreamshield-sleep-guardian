#pragma once
#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include "DreamShieldAcProtocol.h"

namespace dreamshield_ir {
constexpr uint32_t kCooldownMs = 5000;
constexpr size_t kLedgerSize = 64;

inline bool validCommandId(const char *value) {
  if (!value) return false;
  const size_t length = strlen(value);
  if (length < 8 || length > 64) return false;
  for (size_t i = 0; i < length; ++i) {
    const char ch = value[i];
    if (!((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') ||
          (ch >= '0' && ch <= '9') || ch == '-' || ch == '_')) return false;
  }
  return true;
}

struct Attempt {
  char commandId[65] = {};
  dreamshield_ac::State state;
  bool emitted = false;
};

// RAM-only dedupe of the 64 most recent attempted commands in this boot.
// boot_id binding rejects retries from an earlier boot; the server also stores
// its durable command ledger and must not automatically retry unknown outcomes.
class Ledger {
 public:
  const Attempt *find(const char *id) const {
    for (const Attempt &attempt : attempts_)
      if (attempt.commandId[0] && strcmp(attempt.commandId, id) == 0) return &attempt;
    return nullptr;
  }
  Attempt &reserve(const char *id, const dreamshield_ac::State &state, uint32_t now) {
    Attempt &attempt = attempts_[next_];
    next_ = (next_ + 1) % kLedgerSize;
    attempt = Attempt{};
    memcpy(attempt.commandId, id, strlen(id) + 1);
    attempt.state = state;
    lastAttemptMs_ = now;
    hasAttempt_ = true;
    return attempt;
  }
  uint32_t cooldownRemaining(uint32_t now) const {
    if (!hasAttempt_) return 0;
    const uint32_t elapsed = now - lastAttemptMs_;
    return elapsed < kCooldownMs ? kCooldownMs - elapsed : 0;
  }
 private:
  Attempt attempts_[kLedgerSize] = {};
  size_t next_ = 0;
  uint32_t lastAttemptMs_ = 0;
  bool hasAttempt_ = false;
};
}  // namespace dreamshield_ir
