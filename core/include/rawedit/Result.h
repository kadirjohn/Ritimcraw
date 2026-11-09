// rawedit Result types.
//
// Result<void> is a dedicated status struct (has_value + code + detail) and
// must NOT instantiate std::optional<void> — see STEPS/00-START-HERE.md.
// The void specialization below is a full specialization of rawedit::Result,
// so std::optional<void> is never formed anywhere in this codebase.

#pragma once

#include <optional>
#include <string>
#include <utility>

namespace rawedit {

enum class ErrorCode {
    Ok,
    Truncated,
    BadHeader,
    IoError,
    Unsupported
};

inline const char* errorName(ErrorCode code) {
    switch (code) {
        case ErrorCode::Ok:         return "ok";
        case ErrorCode::Truncated:  return "truncated";
        case ErrorCode::BadHeader:  return "badHeader";
        case ErrorCode::IoError:    return "ioError";
        case ErrorCode::Unsupported: return "unsupported";
    }
    return "unknown";
}

// Status-only result for operations that produce no value.
// Dedicated struct: has_value + code + detail. No std::optional<void>.
struct Status {
    bool has_value = false;
    ErrorCode code = ErrorCode::Ok;
    std::string detail;

    static Status ok() { return Status{true, ErrorCode::Ok, {}}; }
    static Status fail(ErrorCode code, std::string detail) {
        return Status{false, code, std::move(detail)};
    }

    bool isOk() const { return has_value; }
    explicit operator bool() const { return has_value; }
};

// Result<void>: same shape as Status. This is a full specialization of the
// Result template — std::optional<void> is never instantiated.
template <class T> struct Result;

template <>
struct Result<void> {
    bool has_value = false;
    ErrorCode code = ErrorCode::Ok;
    std::string detail;

    static Result<void> ok() { return Result<void>{true, ErrorCode::Ok, {}}; }
    static Result<void> fail(ErrorCode code, std::string detail) {
        return Result<void>{false, code, std::move(detail)};
    }

    bool isOk() const { return has_value; }
    explicit operator bool() const { return has_value; }
};

// Result<T> for value-producing operations. The value is absent on failure;
// code + detail say why. Access the object through .value only after ok().
template <class T>
struct Result {
    std::optional<T> value;
    ErrorCode code = ErrorCode::Ok;
    std::string detail;

    Result() = default;

    static Result ok(T v) {
        Result r;
        r.value = std::move(v);
        r.code = ErrorCode::Ok;
        return r;
    }

    static Result fail(ErrorCode code_, std::string detail_) {
        Result r;
        r.value.reset();
        r.code = code_;
        r.detail = std::move(detail_);
        return r;
    }

    bool isOk() const { return value.has_value(); }
    explicit operator bool() const { return value.has_value(); }

    // Access the owned resource from the right layer (00-START-HERE.md):
    // has_value() first, then *value / value->.
    T& operator*() { return *value; }
    const T& operator*() const { return *value; }
    T* operator->() { return &*value; }
    const T* operator->() const { return &*value; }
};

} // namespace rawedit
