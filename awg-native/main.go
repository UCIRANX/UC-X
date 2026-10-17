package main

/*
#include <stdlib.h>
#include <jni.h>

extern const char* awgJstrChars(JNIEnv* env, jstring s);
extern void awgJstrRelease(JNIEnv* env, jstring s, const char* c);
extern jstring awgNewString(JNIEnv* env, const char* s);
*/
import "C"

import (
	"os"
	"sync"
	"syscall"
	"unsafe"

	"github.com/amnezia-vpn/amneziawg-go/v3/conn"
	"github.com/amnezia-vpn/amneziawg-go/v3/device"
	"github.com/amnezia-vpn/amneziawg-go/v3/tun"
)

var (
	mu      sync.Mutex
	lastErr string
	nextID  int32 = 1
	devices       = map[int32]*device.Device{}
)

func jstrGo(env *C.JNIEnv, s C.jstring) string {
	c := C.awgJstrChars(env, s)
	if c == nil {
		return ""
	}
	defer C.awgJstrRelease(env, s, c)
	return C.GoString(c)
}

// turnOn یک تونل AmneziaWG را روی fd رابط VpnService راه می‌اندازد.
func turnOn(iface string, fd int, settings string) (int32, error) {
	dup, err := syscall.Dup(fd)
	if err != nil {
		return 0, err
	}
	if err := syscall.SetNonblock(dup, true); err != nil {
		_ = syscall.Close(dup)
		return 0, err
	}
	tunDev, err := tun.CreateTUNFromFile(os.NewFile(uintptr(dup), "/dev/tun"), 0)
	if err != nil {
		_ = syscall.Close(dup)
		return 0, err
	}
	logger := device.NewLogger(device.LogLevelError, "("+iface+") ")
	dev := device.NewDevice(tunDev, conn.NewDefaultBind(), logger)
	if err := dev.IpcSet(settings); err != nil {
		dev.Close()
		return 0, err
	}
	if err := dev.Up(); err != nil {
		dev.Close()
		return 0, err
	}
	mu.Lock()
	h := nextID
	nextID++
	devices[h] = dev
	mu.Unlock()
	return h, nil
}

//export Java_com_multivpn_app_awg_AwgNative_turnOn
func Java_com_multivpn_app_awg_AwgNative_turnOn(env *C.JNIEnv, cls C.jclass, jIface C.jstring, fd C.jint, jSettings C.jstring) C.jint {
	h, err := turnOn(jstrGo(env, jIface), int(fd), jstrGo(env, jSettings))
	if err != nil {
		mu.Lock()
		lastErr = err.Error()
		mu.Unlock()
		return -1
	}
	return C.jint(h)
}

//export Java_com_multivpn_app_awg_AwgNative_turnOff
func Java_com_multivpn_app_awg_AwgNative_turnOff(env *C.JNIEnv, cls C.jclass, h C.jint) {
	mu.Lock()
	dev := devices[int32(h)]
	delete(devices, int32(h))
	mu.Unlock()
	if dev != nil {
		dev.Close()
	}
}

//export Java_com_multivpn_app_awg_AwgNative_ipcGet
func Java_com_multivpn_app_awg_AwgNative_ipcGet(env *C.JNIEnv, cls C.jclass, h C.jint) C.jstring {
	mu.Lock()
	dev := devices[int32(h)]
	mu.Unlock()
	out := ""
	if dev != nil {
		if s, err := dev.IpcGet(); err == nil {
			out = s
		}
	}
	cs := C.CString(out)
	defer C.free(unsafe.Pointer(cs))
	return C.awgNewString(env, cs)
}

//export Java_com_multivpn_app_awg_AwgNative_lastError
func Java_com_multivpn_app_awg_AwgNative_lastError(env *C.JNIEnv, cls C.jclass) C.jstring {
	mu.Lock()
	msg := lastErr
	mu.Unlock()
	cs := C.CString(msg)
	defer C.free(unsafe.Pointer(cs))
	return C.awgNewString(env, cs)
}

func main() {}
