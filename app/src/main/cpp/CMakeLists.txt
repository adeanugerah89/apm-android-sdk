cmake_minimum_required(VERSION 3.22.1)
project(apmverifier C)

# No optimisation: a null dereference is undefined behaviour, and an optimising compiler is
# entitled to delete it or replace it with a trap. -O0 keeps the fault we are trying to observe.
add_library(apm_crash_trigger SHARED crash_trigger.c)
target_compile_options(apm_crash_trigger PRIVATE -O0)
