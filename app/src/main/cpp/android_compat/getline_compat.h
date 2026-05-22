#pragma once

#include <stdio.h>
#include <sys/types.h>

#if defined(__ANDROID__)
ssize_t getdelim(char **lineptr, size_t *n, int delim, FILE *stream);
ssize_t getline(char **lineptr, size_t *n, FILE *stream);
#ifndef POSIX_FADV_SEQUENTIAL
#define POSIX_FADV_SEQUENTIAL 0
#endif
static inline int android_compat_posix_fadvise(int fd, off_t offset, off_t length, int advice) { (void) fd; (void) offset; (void) length; (void) advice; return 0; }
#define posix_fadvise android_compat_posix_fadvise
#endif
