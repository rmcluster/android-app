#pragma once

#include <stddef.h>
#include <stdio.h>
#include <sys/types.h>

#if defined(__ANDROID__)
ssize_t getdelim(char **lineptr, size_t *n, int delim, FILE *stream);
ssize_t getline(char **lineptr, size_t *n, FILE *stream);
#ifndef POSIX_FADV_SEQUENTIAL
#define POSIX_FADV_SEQUENTIAL 0
#endif
#ifndef POSIX_MADV_WILLNEED
#define POSIX_MADV_WILLNEED 3
#endif
#ifndef POSIX_MADV_RANDOM
#define POSIX_MADV_RANDOM 1
#endif
static inline int android_compat_posix_fadvise(int fd, off_t offset, off_t length, int advice) { (void) fd; (void) offset; (void) length; (void) advice; return 0; }
static inline int android_compat_posix_madvise(void *addr, size_t len, int advice) { (void) addr; (void) len; (void) advice; return 0; }
#define posix_fadvise android_compat_posix_fadvise
#define posix_madvise android_compat_posix_madvise
#endif
