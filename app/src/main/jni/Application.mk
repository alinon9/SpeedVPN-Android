APP_OPTIM := release
APP_PLATFORM := android-24
APP_ABI := arm64-v8a armeabi-v7a x86_64
# JNI class = hev.htproxy.TProxyService (see app/src/main/java/hev/htproxy/TProxyService.java)
APP_CFLAGS := -O3 -DPKGNAME=hev/htproxy -DCLSNAME=TProxyService
APP_CPPFLAGS := -O3 -std=c++11
NDK_TOOLCHAIN_VERSION := clang
