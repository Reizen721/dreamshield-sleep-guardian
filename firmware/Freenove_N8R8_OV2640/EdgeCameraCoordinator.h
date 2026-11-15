#pragma once
#include <Arduino.h>
#include <atomic>
#include <freertos/FreeRTOS.h>
#include <freertos/semphr.h>

// One owner at a time for camera fb_get/fb_return and orientation changes.
// A NN worker copies JPEG and returns the frame before expensive inference.
class EdgeCameraCoordinator {
 public:
  bool begin() {
    mutex_ = xSemaphoreCreateMutex();
    return mutex_ != nullptr;
  }
  bool lock(uint32_t timeoutMs = 0) {
    return mutex_ && xSemaphoreTake(mutex_, pdMS_TO_TICKS(timeoutMs)) == pdTRUE;
  }
  void unlock() { if (mutex_) xSemaphoreGive(mutex_); }
  void orientationChanged() { generation_.fetch_add(1); }
  uint32_t generation() const { return generation_.load(); }
 private:
  SemaphoreHandle_t mutex_ = nullptr;
  std::atomic<uint32_t> generation_{0};
};
