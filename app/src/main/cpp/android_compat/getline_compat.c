#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/types.h>

#if defined(__ANDROID__)

ssize_t getdelim(char **lineptr, size_t *n, int delim, FILE *stream) {
    if (lineptr == NULL || n == NULL || stream == NULL) {
        errno = EINVAL;
        return -1;
    }

    if (*lineptr == NULL || *n == 0) {
        *n = 128;
        *lineptr = (char *) malloc(*n);
        if (*lineptr == NULL) {
            return -1;
        }
    }

    size_t position = 0;
    int ch = 0;

    while ((ch = fgetc(stream)) != EOF) {
        if (position + 1 >= *n) {
            size_t new_size = (*n) * 2;
            char *new_buffer = (char *) realloc(*lineptr, new_size);
            if (new_buffer == NULL) {
                return -1;
            }
            *lineptr = new_buffer;
            *n = new_size;
        }

        (*lineptr)[position++] = (char) ch;
        if (ch == delim) {
            break;
        }
    }

    if (position == 0 && ch == EOF) {
        return -1;
    }

    (*lineptr)[position] = '\0';
    return (ssize_t) position;
}

ssize_t getline(char **lineptr, size_t *n, FILE *stream) {
    return getdelim(lineptr, n, '\n', stream);
}

#endif
