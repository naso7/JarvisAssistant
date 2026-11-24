cmake_minimum_required(VERSION 3.22.1)
#
project("jarvis" LANGUAGES CXX C)
#
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
#
## Отключаем сборку лишних консольных утилит и тестов внутри llama и whisper
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
#
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
#
## Собираем движки как статические библиотеки, чтобы избежать конфликтов символов ggml
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
#
## =====================================================================
## 1. ПОДКЛЮЧАЕМ ДВИЖКИ ИИ (Слух и Мозг)
## =====================================================================
add_subdirectory(third_party/llama.cpp)
add_subdirectory(third_party/whisper.cpp)
#
## =====================================================================
## 2. СОЗДАЕМ НАШУ ГЛАВНУЮ БИБЛИОТЕКУ ДЛЯ СВЯЗИ С KOTLIN
## =====================================================================
add_library(${CMAKE_PROJECT_NAME} SHARED
   native-lib.cpp
        core/whisper_engine.cpp
        core/llama_engine.cpp
)
#
## Указываем пути к заголовочным файлам (llama.h и whisper.h)
target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/llama.cpp/include
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/whisper.cpp/include
    ${CMAKE_CURRENT_SOURCE_DIR}/core
)
#
## Находим системную библиотеку логирования Android (чтобы видеть printf/LOG в Logcat)
find_library(log-lib log)
#
## Линкуем всё в единый нативный модуль
target_link_libraries(${CMAKE_PROJECT_NAME} PRIVATE
    llama
    whisper
    ${log-lib}
)