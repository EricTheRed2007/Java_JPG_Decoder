SOURCES := $(wildcard *.java)
BUILD_DIR := build

.PHONY: all clean

all:
	if not exist "$(BUILD_DIR)" mkdir "$(BUILD_DIR)"
	javac -d "$(BUILD_DIR)" $(SOURCES)

clean:
	if exist "$(BUILD_DIR)" rmdir /s /q "$(BUILD_DIR)"
