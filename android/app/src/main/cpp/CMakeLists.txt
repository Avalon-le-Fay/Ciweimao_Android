cmake_minimum_required(VERSION 3.22.1)
project(cwmtransport C)

add_library(cwmtransport SHARED official_transport.c)
target_compile_features(cwmtransport PRIVATE c_std_11)
target_compile_options(cwmtransport PRIVATE -Wall -Wextra -Werror)
target_link_options(cwmtransport PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(cwmtransport PRIVATE dl)
