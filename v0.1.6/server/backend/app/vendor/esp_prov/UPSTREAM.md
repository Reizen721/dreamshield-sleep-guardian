# Vendored provisioning protocol

Source: Espressif ESP-IDF v5.5.1, commit
`fcae32885b0296b32044cb99ecbdc50d98dddb83`.

Upstream repository: https://github.com/espressif/esp-idf/tree/fcae32885b0296b32044cb99ecbdc50d98dddb83

License: Apache-2.0; full upstream `LICENSE` is included. Original SPDX
notices are retained in handwritten upstream files. Generated protobuf
files originate in `components/protocomm/python` and
`components/wifi_provisioning/python`. Security 1 originates in
`tools/esp_prov/security`; conversion helpers originate in
`tools/esp_prov/utils`.

Local changes: imports are package-relative so no `IDF_PATH`, global
`sys.path`, or global `proto` module is required. Package initializers are
minimal and do not import Security 0/2 implementations. No upstream
interactive CLI or verbose Wi-Fi provisioning functions are used.
Security 0/2 protobuf definitions are included only because the upstream
session descriptor depends on them; DreamShield negotiates Security 1
only and requires a device-specific proof of possession.

Upstream original SHA-256 hashes before relative-import changes:

| File | SHA-256 |
| --- | --- |
| security/security1.py | e1f2e4d5bb92fd4db4065be699b012e265091952fcdbfa397c67c8556109041c |
| security/security.py | 37a88d266d730154b0b914e51b68e0e50d2565a192cedf76ac57f2743f79c58e |
| proto/constants_pb2.py | 24077ad3a8830c9035dc5ca22b9aafd74218bcfbcf5ae0ba4cbfd2446b9199a9 |
| proto/sec0_pb2.py | 87232aebc640e012b6c3b5184eaa10296c9679890d985c9ad9067b5f1aa22e39 |
| proto/sec1_pb2.py | 93be151ecf6890b4464aaca77ca892693997c49d2308258a61d202a44fc2fda8 |
| proto/sec2_pb2.py | 51bdbbefabc8a948f7307f9b682e32fbfcaaa1b8bee34e16cb2fc1d937230379 |
| proto/session_pb2.py | 8130c80be0db49c86d6897f9f48ab2cb7fb676d4886d15e549bb908324aa767d |
| proto/wifi_config_pb2.py | 521d522dfaa079f33f2b614f1d3eec0e4b84b76c65c9b806b0d5a5fc23fa347d |
| proto/wifi_constants_pb2.py | 56b235c45ac9a299e85a33af9ccb70e8820c878d207d843c0bd3cc5b9fa842ec |
| proto/wifi_scan_pb2.py | 82d609a9cb8f15271993dc3b6677f16a8695c668a3eb494216060fbef9ad46a5 |
| proto/wifi_ctrl_pb2.py | d351e8af2039ba751d0cdb77b6bfc3d6e157127d026d1312bbb79ed3708d151b |
| utils/convenience.py | fadbef74d036079ada0d7ed249ddabc26c8dd10d56aa910cfccf7ed9f7c99d01 |

Transport, bounded task controller, UTF-8 SSID handling, input validation,
and sanitized status reporting are DreamShield code in
`ble_provisioning.py` rather than modifications to the upstream protocol.
