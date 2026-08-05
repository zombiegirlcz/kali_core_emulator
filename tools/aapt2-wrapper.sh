#!/bin/bash
# Wrapper to run x86_64 aapt2 via qemu-user on arm64
export LD_LIBRARY_PATH=/tmp/libc6_extract/usr/lib/x86_64-linux-gnu
exec qemu-x86_64 "$ANDROID_HOME/build-tools/36.0.0/aapt2" "$@"
