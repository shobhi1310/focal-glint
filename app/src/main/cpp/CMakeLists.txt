cmake_minimum_required(VERSION 3.22)
project(focal_intelligence)

include(FetchContent)

FetchContent_Declare(
    sentencepiece
    GIT_REPOSITORY https://github.com/google/sentencepiece.git
    GIT_TAG        v0.2.0
)
set(SPM_ENABLE_SHARED   OFF CACHE BOOL "" FORCE)
set(SPM_ENABLE_TCMALLOC OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(sentencepiece)

# SentencePiece's CLI executables (spm_encode, etc.) use Android's liblog via
# their bundled protobuf-lite but don't link -llog themselves. They don't end up
# in the APK (executables are stripped), but they must link successfully.
# sentencepiece_trainer.cc and builder.cc (both in sentencepiece_train-static) emit
# -Winconsistent-missing-override and -Wunused-const-variable under clang/NDK.
# These are upstream issues in SentencePiece v0.2.0; suppress per-target rather
# than globally so our own code still gets full warnings.
if(TARGET sentencepiece_train-static)
    target_compile_options(sentencepiece_train-static PRIVATE
        -Wno-inconsistent-missing-override
        -Wno-unused-const-variable
    )
endif()

foreach(spm_tool spm_encode spm_decode spm_normalize spm_train spm_export_vocab)
    if(TARGET ${spm_tool})
        target_link_libraries(${spm_tool} log)
    endif()
endforeach()

add_library(focal_intelligence SHARED sentencepiece_jni.cpp)

target_include_directories(focal_intelligence PRIVATE
    ${sentencepiece_SOURCE_DIR}/src
)

target_link_libraries(focal_intelligence
    sentencepiece-static
    log
)
