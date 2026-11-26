cmake_minimum_required(VERSION 3.22.1)

project(maya_native)

add_library(
    maya_native
    SHARED
    native-lib.cpp
)

find_library(
    log-lib
    log
)

target_link_libraries(
    maya_native
    ${log-lib}
)
