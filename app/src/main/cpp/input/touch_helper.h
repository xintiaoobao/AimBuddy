#ifndef TOUCH_HELPER_H
#define TOUCH_HELPER_H

#include <jni.h>

enum class TouchBackend : int {
    UINPUT = 0,
    SHIZUKU = 1,
};

/**
 * TouchHelper - uinput-based touch simulation
 *
 * Template-matched touch pipeline.
 * Creates a virtual touchscreen via /dev/uinput.
 * Requires root access.
 */
class TouchHelper {
public:
    TouchHelper();
    ~TouchHelper();

    /**
     * Initialize touch system
     * @return true if successful (requires root)
     */
    bool init();

    /**
     * Select active touch backend.
     */
    void setBackend(TouchBackend backend);

    /**
     * Expose JVM and activity class used for static bridge callbacks.
     */
    void setJniBridge(JavaVM* vm, JNIEnv* env, jobject activityInstance);

    /**
     * Report whether Kotlin Shizuku bridge is ready.
     */
    void setShizukuBridgeAvailable(bool available);

    /**
     * Set screen dimensions for coordinate scaling
     */
    void setScreenSize(int width, int height);

    /**
     * Simulate touch down at screen coordinates
     */
    void touchDown(int slot, float x, float y);

    /**
     * Simulate touch move at screen coordinates
     */
    void touchMove(int slot, float x, float y);

    /**
     * Simulate touch up
     */
    void touchUp(int slot);

    /**
     * Cleanup
     */
    void shutdown();

    bool isInitialized() const;

private:
    TouchBackend backend_;
    bool initialized_;
    bool shizukuBridgeAvailable_;

    JavaVM* javaVm_;
    jclass activityClassGlobal_;
    jmethodID shizukuMoveMethod_;
    jmethodID shizukuUpMethod_;

    bool initUinput();
    bool initShizuku();
    void shutdownUinput();
    void releaseActiveTouch();

    bool callShizukuMove(float x, float y, bool isFirst);
    bool callShizukuUp();
    bool ensureJniMethods(JNIEnv* env);
};

#endif // TOUCH_HELPER_H