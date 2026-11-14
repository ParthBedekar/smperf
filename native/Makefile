# Builds the mperf engine for the current OS into bin/mperf (bin/mperf.exe on Windows).
#   make            build
#   make clean      remove the binary
#   make universal  (macOS only) arm64 + x86_64 universal binary
#   make windows    cross-compile mperf.exe from Linux/macOS with a MinGW-w64 compiler:
#                     make windows WINCC=x86_64-w64-mingw32-gcc
#                     make windows WINCC="zig cc -target x86_64-windows-gnu"
#   make test       unit tests for the portable helpers (no Windows needed)
#
# On Windows itself (MSYS2/MinGW-w64 gcc, `mingw32-make`) plain `make` just works.
UNAME_S := $(shell uname -s 2>/dev/null)

COMMON  := src/cli.c src/runner.c src/stats.c src/util.c src/topology_common.c
CFLAGS  ?= -O2 -Wall -Wextra
CFLAGS  += -std=gnu11 -I.

WIN_SOURCES := $(COMMON) platform/windows/topology_windows.c platform/windows/run_once_windows.c \
               platform/windows/platform_windows.c
WIN_CFLAGS  := -D__USE_MINGW_ANSI_STDIO=1
WIN_LDLIBS  := -lpowrprof -lpsapi -ladvapi32 -lshell32 -lm
WINCC       ?= x86_64-w64-mingw32-gcc

ifeq ($(OS),Windows_NT)
  CC       ?= gcc
  SOURCES  := $(WIN_SOURCES)
  CFLAGS   += $(WIN_CFLAGS)
  LDLIBS   := $(WIN_LDLIBS)
  TARGET   := bin/mperf.exe
else ifeq ($(UNAME_S),Darwin)
  CC       ?= clang
  SOURCES  := $(COMMON) platform/posix/platform_posix.c platform/posix/run_once_posix.c \
              platform/macos/topology_macos.c
  LDLIBS   :=
  TARGET   := bin/mperf
else
  CC       ?= gcc
  SOURCES  := $(COMMON) platform/posix/platform_posix.c platform/posix/run_once_posix.c \
              platform/linux/topology_linux.c
  LDLIBS   := -lm
  TARGET   := bin/mperf
endif

HEADERS := $(wildcard include/*.h)

$(TARGET): $(SOURCES) $(HEADERS)
	@mkdir -p bin
	$(CC) $(CFLAGS) -o $@ $(SOURCES) $(LDLIBS)

universal: $(SOURCES) $(HEADERS)
	@mkdir -p bin
	$(CC) $(CFLAGS) -arch arm64 -arch x86_64 -o bin/mperf $(SOURCES) $(LDLIBS)

windows: $(WIN_SOURCES) $(HEADERS)
	@mkdir -p bin
	$(WINCC) $(CFLAGS) $(WIN_CFLAGS) -o bin/mperf.exe $(WIN_SOURCES) $(WIN_LDLIBS)

test: tests/test_util.c src/util.c $(HEADERS)
	@mkdir -p bin
	$(CC) -std=gnu11 -Wall -Wextra -I. -o bin/test_util tests/test_util.c src/util.c && ./bin/test_util

clean:
	rm -f bin/mperf bin/mperf.exe bin/test_util

.PHONY: universal windows test clean
