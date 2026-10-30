cmake_minimum_required(VERSION 3.22.1)
project(opencode_pty C)
add_library(opencode_pty SHARED pty.c)
target_compile_options(opencode_pty PRIVATE -Wall -Wextra -Werror)
# 16 KB page alignment (Play requirement for targetSdk 35+).
target_link_options(opencode_pty PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(opencode_pty log)
