#pragma once
#include <stdint.h>
#include <stddef.h>
#include <string.h>

// EXPERIMENTAL PROFILE: YAPOFB14 basic 128-bit, not generic GREE 64-bit.
// The user's 26/27 C captures prove the basic two-group layout, NOT acceptance
// of every state below by their appliance. Mode/fan/swing field definitions are
// adapted from IRremoteESP8266 PR #2298 (open/unmerged when checked 2026-10-08):
// https://github.com/crankyoldgit/IRremoteESP8266/pull/2298
// Pinned source (full commit):
// https://github.com/shirok1/IRremoteESP8266/tree/f089c7cd9ff854b5ba5eda72987c56d34501fd1d
// src/ir_Gree.h (GreeProtocol), src/ir_Gree.cpp (setters/sendGree/fixup),
// test/ir_Gree_test.cpp (Yapofb14HeatDefaults and captured 128-bit states).
// Checksum: IRremoteESP8266 v2.9.0 src/ir_Kelvinator.cpp calcBlockChecksum.
// Upstream Copyright 2016-2022 David Conran; Copyright 2017 Ville Skytta;
// YAPOFB14 extensions by shirok1 and IRremoteESP8266 contributors.
// Adaptations in this header are LGPL-2.1-or-later, like upstream;
// see LICENSE_IRREMOTEESP8266.txt. No upstream library is silently upgraded.
// This bounded encoder has no timer/extended third group or raw-code input.
namespace dreamshield_ac {
constexpr size_t kStateBytes = 16;
constexpr size_t kRawDurations = 280;
constexpr const char *kProfile = "yapofb14_basic_128";
enum Mode : uint8_t { Auto = 0, Cool = 1, Dry = 2, FanOnly = 3, Heat = 4 };

struct State {
  bool power = false;
  uint8_t mode = Cool;
  uint8_t setpoint = 26;
  uint8_t fan = 0;  // YAPOFB14 native: auto=0, low=1, medium=3, high=5.
  bool swingVertical = false;
  bool swingHorizontal = false;
};

inline bool sameState(const State &a, const State &b) {
  return a.power == b.power && a.mode == b.mode && a.setpoint == b.setpoint &&
      a.fan == b.fan && a.swingVertical == b.swingVertical &&
      a.swingHorizontal == b.swingHorizontal;
}
inline bool canonical(const State &s) {
  return s.mode <= Heat && s.setpoint >= 16 && s.setpoint <= 30 &&
      (s.fan == 0 || s.fan == 1 || s.fan == 3 || s.fan == 5) &&
      (s.mode != Auto || s.setpoint == 25) && (s.mode != Dry || s.fan == 1);
}
inline const char *modeName(uint8_t mode) {
  static const char *const names[] = {"auto", "cool", "dry", "fan", "heat"};
  return mode <= Heat ? names[mode] : "invalid";
}
inline const char *fanName(uint8_t fan) {
  switch (fan) {
    case 0: return "auto";
    case 1: return "low";
    case 3: return "medium";
    case 5: return "high";
    default: return "invalid";
  }
}
inline bool parseOnOff(const char *text, bool &value) {
  if (text && strcmp(text, "on") == 0) { value = true; return true; }
  if (text && strcmp(text, "off") == 0) { value = false; return true; }
  return false;
}
inline bool parseMode(const char *text, uint8_t &value) {
  if (!text) return false;
  for (uint8_t mode = Auto; mode <= Heat; ++mode)
    if (strcmp(text, modeName(mode)) == 0) { value = mode; return true; }
  return false;
}
inline bool parseFan(const char *text, uint8_t &value) {
  if (!text) return false;
  const uint8_t values[] = {0, 1, 3, 5};
  for (uint8_t fan : values)
    if (strcmp(text, fanName(fan)) == 0) { value = fan; return true; }
  return false;
}
inline bool parseSetpoint(const char *text, uint8_t &value) {
  // Reject whitespace, decimal notation, signs and leading zeroes, not just
  // out-of-range numbers. The response must describe exactly what was asked.
  if (!text || strlen(text) != 2 || text[0] < '1' || text[0] > '3' ||
      text[1] < '0' || text[1] > '9') return false;
  const int number = (text[0] - '0') * 10 + text[1] - '0';
  if (number < 16 || number > 30) return false;
  value = static_cast<uint8_t>(number);
  return true;
}

inline uint8_t blockChecksum(const uint8_t *block) {
  uint8_t sum = 10;
  for (uint8_t i = 0; i < 4; ++i) sum += block[i] & 0x0F;
  for (uint8_t i = 4; i < 7; ++i) sum += block[i] >> 4;
  return sum & 0x0F;
}

inline bool encode(const State &s, uint8_t bytes[kStateBytes]) {
  if (!bytes || !canonical(s)) return false;
  memset(bytes, 0, kStateBytes);
  const uint8_t basicFan = s.fan > 3 ? 3 : s.fan;
  bytes[0] = s.mode | (s.power ? 0x08 : 0) | (basicFan << 4) |
      ((s.swingVertical || s.swingHorizontal) ? 0x40 : 0);
  bytes[1] = s.setpoint - 16;
  // In HEAT, PR #2298 captured test #107 uses Xfan=1/AuxHeatDefault=0
  // for explicit auxiliary-heater OFF. Leaving both at 0 means explicit ON;
  // setting AuxHeatDefault means the remote's default heater policy. Do not
  // enable either implicitly. In non-HEAT, Xfan itself is simply OFF.
  bytes[2] = s.mode == Heat ? 0x80 : 0;
  bytes[3] = 0x50;
  bytes[4] = (s.swingVertical ? 1 : 0) | (s.swingHorizontal ? 0x10 : 0);
  // New profile fixed options: Sleep/Quiet/Turbo/Timer/iFeel/Econo/WiFi OFF,
  // display-light OFF (Light=0 and inverse LightOff=1), Celsius only. These
  // are explicit complete-state values, NOT preservation of appliance state.
  bytes[14] = (s.fan << 4) | 0x80;
  memcpy(bytes + 8, bytes, 3);
  bytes[11] = 0x70;
  bytes[7] = blockChecksum(bytes) << 4;
  bytes[15] = blockChecksum(bytes + 8) << 4;
  return true;
}

inline bool buildRaw(const State &s, uint16_t *raw, size_t capacity) {
  uint8_t bytes[kStateBytes];
  if (!raw || capacity < kRawDurations || !encode(s, bytes)) return false;
  size_t n = 0;
  for (uint8_t group = 0; group < 2; ++group) {
    raw[n++] = 9000;
    raw[n++] = 4500;
    for (uint8_t half = 0; half < 2; ++half) {
      for (uint8_t byte = 0; byte < 4; ++byte) {
        const uint8_t value = bytes[group * 8 + half * 4 + byte];
        for (uint8_t bit = 0; bit < 8; ++bit) {
          raw[n++] = 620;
          raw[n++] = (value & (1U << bit)) ? 1600 : 540;
        }
      }
      if (!half) {
        for (uint8_t bit = 0; bit < 3; ++bit) {
          raw[n++] = 620;
          raw[n++] = bit == 1 ? 1600 : 540;  // LSB-first 010 separator.
        }
      }
      raw[n++] = 620;
      raw[n++] = (half == 1 && group == 0) ? 40000 : 19980;
    }
  }
  return n == kRawDurations;
}
}  // namespace dreamshield_ac
