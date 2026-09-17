cmake_minimum_required(VERSION 3.25)

if(POLICY CMP0144)
    cmake_policy(SET CMP0144 NEW)
endif()

project(Zircon2026 LANGUAGES CXX)

if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
    set(CMAKE_BUILD_TYPE Release CACHE STRING "Build type" FORCE)
endif()

include(CTest)

find_program(SBT_EXECUTABLE sbt REQUIRED)

set(ZIRCON_RTL_DIR "${CMAKE_CURRENT_SOURCE_DIR}/generated")
set(ZIRCON_RTL_TOP "${ZIRCON_RTL_DIR}/ZirconCore.sv")

file(GLOB_RECURSE ZIRCON_CHISEL_SOURCES CONFIGURE_DEPENDS
    "${CMAKE_CURRENT_SOURCE_DIR}/src/main/scala/*.scala"
)
file(GLOB ZIRCON_SBT_PROJECT_SOURCES CONFIGURE_DEPENDS
    "${CMAKE_CURRENT_SOURCE_DIR}/project/*.sbt"
    "${CMAKE_CURRENT_SOURCE_DIR}/project/*.properties"
)

if(NOT EXISTS "${ZIRCON_RTL_TOP}")
    execute_process(
        COMMAND "${SBT_EXECUTABLE}" --batch "runMain Elaborate --simulation ${ZIRCON_RTL_DIR}"
        WORKING_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}"
        COMMAND_ERROR_IS_FATAL ANY
    )
endif()

add_custom_command(
    OUTPUT "${ZIRCON_RTL_TOP}"
    COMMAND "${CMAKE_COMMAND}" -E make_directory "${ZIRCON_RTL_DIR}"
    COMMAND "${SBT_EXECUTABLE}" --batch "runMain Elaborate --simulation ${ZIRCON_RTL_DIR}"
    DEPENDS
        ${ZIRCON_CHISEL_SOURCES}
        ${ZIRCON_SBT_PROJECT_SOURCES}
        "${CMAKE_CURRENT_SOURCE_DIR}/build.sbt"
    WORKING_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}"
    COMMENT "Elaborating ZirconCore RTL"
    VERBATIM
)

add_custom_target(zircon-rtl DEPENDS "${ZIRCON_RTL_TOP}")

add_subdirectory(ZirconSim)
