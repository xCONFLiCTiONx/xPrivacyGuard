#!/usr/bin/env bash
set -euo pipefail

# Cross-platform (Linux/macOS) native build script for Aether (Android).
# Used by GitHub Actions and F-Droid buildserver.
#
# Usage:
#   ./scripts/build-aether-android.sh [--fetch-only] [--build-only] [--offline]
#                                     [--targets "arm64-v8a x86_64"] [--api 26]

AETHER_REPO_URL="https://github.com/CluvexStudio/Aether.git"
AETHER_PINNED_COMMIT="ef06971101ba3d4b30f0bb317a2bba81c9b55ca2"

TARGETS="arm64-v8a x86_64"
API_LEVEL="26"
FETCH_ONLY=false
BUILD_ONLY=false
OFFLINE=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --fetch-only)
            FETCH_ONLY=true
            shift
            ;;
        --build-only)
            BUILD_ONLY=true
            shift
            ;;
        --offline)
            OFFLINE=true
            shift
            ;;
        --targets)
            TARGETS="$2"
            shift 2
            ;;
        --api)
            API_LEVEL="$2"
            shift 2
            ;;
        *)
            echo "Unknown argument: $1" >&2
            exit 1
            ;;
    esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
AETHER_DIR="${PROJECT_DIR}/third_party/Aether"
PATCH_DIR="${PROJECT_DIR}/patches/aether"
OUTPUT_DIR="${PROJECT_DIR}/app/src/main/jniLibs"

require_cmd() {
    if ! command -v "$1" >/dev/null 2>&1; then
        echo "ERROR: Required command '$1' not found in PATH." >&2
        exit 1
    fi
}

abi_to_rust_target() {
    case "$1" in
        arm64-v8a)   echo "aarch64-linux-android" ;;
        x86_64)      echo "x86_64-linux-android" ;;
        armeabi-v7a) echo "armv7-linux-androideabi" ;;
        *)
            echo "ERROR: Unsupported Android ABI '$1'." >&2
            exit 1
            ;;
    esac
}

ensure_aether_source() {
    require_cmd git

    if [[ ! -f "${AETHER_DIR}/aether/Cargo.toml" ]]; then
        if [[ "${BUILD_ONLY}" == "true" ]]; then
            echo "ERROR: Aether source missing at ${AETHER_DIR} and --build-only was specified." >&2
            exit 1
        fi
        echo "==> Cloning pinned Aether (${AETHER_PINNED_COMMIT})..."
        mkdir -p "$(dirname "${AETHER_DIR}")"
        rm -rf "${AETHER_DIR}"
        git clone "${AETHER_REPO_URL}" "${AETHER_DIR}"
        git -C "${AETHER_DIR}" checkout --detach "${AETHER_PINNED_COMMIT}"
        git -C "${AETHER_DIR}" submodule update --init --recursive
    fi

    local current_commit
    current_commit="$(git -C "${AETHER_DIR}" rev-parse HEAD 2>/dev/null || true)"
    if [[ -n "${current_commit}" && "${current_commit}" != "${AETHER_PINNED_COMMIT}" ]]; then
        echo "WARNING: Aether HEAD (${current_commit}) differs from pinned (${AETHER_PINNED_COMMIT})." >&2
    fi
}

apply_patches() {
    if [[ ! -d "${PATCH_DIR}" ]]; then
        echo "ERROR: Patch directory not found: ${PATCH_DIR}" >&2
        exit 1
    fi

    shopt -s nullglob
    local patches=("${PATCH_DIR}"/*.patch)
    shopt -u nullglob

    if [[ ${#patches[@]} -eq 0 ]]; then
        echo "ERROR: No patch files found in ${PATCH_DIR}" >&2
        exit 1
    fi

    pushd "${AETHER_DIR}" >/dev/null
    for patch in "${patches[@]}"; do
        local name
        name="$(basename "${patch}")"
        if git apply --reverse --check "${patch}" >/dev/null 2>&1; then
            echo "==> Aether patch already applied: ${name}"
        else
            git apply --check "${patch}"
            git apply "${patch}"
            echo "==> Applied Aether patch: ${name}"
        fi
    done
    popd >/dev/null
}

fetch_cargo_deps() {
    require_cmd cargo
    echo "==> Fetching Rust dependencies for Aether..."
    pushd "${AETHER_DIR}/aether" >/dev/null
    cargo fetch --locked 2>/dev/null || cargo fetch
    popd >/dev/null
}

build_aether() {
    require_cmd cargo
    require_cmd rustup
    require_cmd cargo-ndk

    if [[ -z "${ANDROID_NDK_HOME:-}" && -z "${ANDROID_NDK_ROOT:-}" && -z "${NDK_HOME:-}" && -z "${ANDROID_HOME:-}" ]]; then
        echo "ERROR: Set ANDROID_NDK_HOME (or ANDROID_NDK_ROOT / NDK_HOME / ANDROID_HOME)." >&2
        exit 1
    fi

    local installed_targets
    installed_targets="$(rustup target list --installed)"
    local ndk_args=()

    for abi in ${TARGETS}; do
        local rust_target
        rust_target="$(abi_to_rust_target "${abi}")"
        if ! grep -qx "${rust_target}" <<<"${installed_targets}"; then
            echo "ERROR: Rust target '${rust_target}' is missing. Install with: rustup target add ${rust_target}" >&2
            exit 1
        fi
        ndk_args+=("-t" "${abi}")
    done

    mkdir -p "${OUTPUT_DIR}"

    local cargo_flags=("build" "--lib" "--release")
    if [[ "${OFFLINE}" == "true" ]]; then
        cargo_flags+=("--offline")
    fi

    echo "==> Building Aether for Android ABIs: ${TARGETS} (API ${API_LEVEL})"
    pushd "${AETHER_DIR}/aether" >/dev/null
    cargo ndk "${ndk_args[@]}" --platform "${API_LEVEL}" -o "${OUTPUT_DIR}" "${cargo_flags[@]}"
    popd >/dev/null

    for abi in ${TARGETS}; do
        local lib="${OUTPUT_DIR}/${abi}/libaether.so"
        if [[ ! -f "${lib}" ]]; then
            echo "ERROR: Expected library not produced: ${lib}" >&2
            exit 1
        fi
        echo "==> Built: ${lib}"
    done
}

ensure_aether_source
apply_patches

if [[ "${FETCH_ONLY}" == "true" ]]; then
    fetch_cargo_deps
    echo "==> Aether source and Cargo dependencies are ready."
    exit 0
fi

build_aether
echo "==> Aether Android libraries built successfully."
