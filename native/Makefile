# Builds the mperf engine for the current OS into bin/mperf.
#   make            build
#   make clean      remove bin/mperf
#   make universal  (macOS only) arm64 + x86_64 universal binary
UNAME_S := $(shell uname -s)

COMMON  := src/cli.c src/runner.c src/stats.c src/util.c src/topology_common.c
CFLAGS  ?= -O2 -Wall -Wextra
CFLAGS  += -std=gnu11 -I.

ifeq ($(UNAME_S),Darwin)
  CC      ?= clang
  SOURCES := $(COMMON) platform/macos/topology_macos.c
  LDLIBS  :=
else
  CC      ?= gcc
  SOURCES := $(COMMON) src/topology_linux.c
  LDLIBS  := -lm
endif

HEADERS := $(wildcard include/*.h)

bin/mperf: $(SOURCES) $(HEADERS)
	@mkdir -p bin
	$(CC) $(CFLAGS) -o $@ $(SOURCES) $(LDLIBS)

universal: $(SOURCES) $(HEADERS)
	@mkdir -p bin
	$(CC) $(CFLAGS) -arch arm64 -arch x86_64 -o bin/mperf $(SOURCES) $(LDLIBS)

clean:
	rm -f bin/mperf

.PHONY: universal clean

