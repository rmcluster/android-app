#if defined(__ANDROID__)

#include <errno.h>
#include <math.h>
#include <stdlib.h>
#include <sys/time.h>
#include <time.h>

/* libm on API 16 is missing several functions the NDK links as @LIBC. */

float log2f(float x) {
    return logf(x) * 1.44269504088896341f; /* 1 / ln(2) */
}

double log2(double x) {
    return log(x) * 1.44269504088896341;
}

long lroundf(float x) {
    if (x >= 0.0f) {
        return (long) (x + 0.5f);
    }
    return (long) (x - 0.5f);
}

long lround(double x) {
    if (x >= 0.0) {
        return (long) (x + 0.5);
    }
    return (long) (x - 0.5);
}

int posix_memalign(void **memptr, size_t alignment, size_t size) {
    if (memptr == NULL) {
        return EINVAL;
    }
    void *ptr = memalign(alignment, size);
    if (ptr == NULL) {
        return ENOMEM;
    }
    *memptr = ptr;
    return 0;
}

int clock_gettime(clockid_t clk_id, struct timespec *tp) {
    (void) clk_id;
    if (tp == NULL) {
        errno = EINVAL;
        return -1;
    }
    struct timeval tv;
    if (gettimeofday(&tv, NULL) != 0) {
        return -1;
    }
    tp->tv_sec = tv.tv_sec;
    tp->tv_nsec = (long) tv.tv_usec * 1000L;
    return 0;
}

#endif
