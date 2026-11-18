// Android system properties: none on Linux (linux/shim/shim_sys.c answers the product ones from /sys/class/dmi and /proc/device-tree)
#pragma once
#define PROP_VALUE_MAX 92
int __system_property_get(const char *name, char *value);
