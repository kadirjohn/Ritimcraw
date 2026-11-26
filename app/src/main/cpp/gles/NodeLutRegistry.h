// NodeLutRegistry: the process-wide owner of the node-LUT cubes the node chain wire refers to (F1-8d).
//
// The chain wire is a flat FloatArray, so a node's LUT crosses it as a small integer HANDLE (slot
// kNodeChainLutHandle), never a string. Kotlin registers a cube file once (nativeRegisterNodeLut: parse
// the .cube with core's parseCubeFile, cached by path) and puts the returned handle in the node's wire
// record; decodeNodeChainWire turns the handle back into the registry-owned `const rawedit::Lut*` the
// rawedit::GradeNode borrows. The parsed tables live for the process (handles are never reused), so the
// borrowed pointer stays valid across sessions, renders and the export threads; a cube file is an
// immutable, content-addressed library blob (its id is the fingerprint), so caching by path is sound.
#pragma once

#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include <rawedit/Lut.h>

namespace gles {

class NodeLutRegistry {
public:
    // Registers (parses on first use) the cube at [path]; returns its handle (> 0) or 0 when the file does not
    // parse (the reason lands in [error] when given).
    static int add(const std::string& path, std::string* error = nullptr) {
        State& s = state();
        std::lock_guard<std::mutex> lock(s.mu);
        const auto it = s.byPath.find(path);
        if (it != s.byPath.end()) return it->second;
        auto parsed = rawedit::parseCubeFile(path);
        if (!parsed.isOk()) {
            if (error != nullptr) *error = parsed.detail;
            return 0;
        }
        s.luts.push_back(std::make_unique<rawedit::Lut>(std::move(*parsed.value)));
        const int handle = static_cast<int>(s.luts.size());  // 1-based, never reused
        s.byPath[path] = handle;
        return handle;
    }

    // The registry-owned LUT for [handle], or nullptr for 0 / an unknown handle.
    static const rawedit::Lut* get(int handle) {
        State& s = state();
        std::lock_guard<std::mutex> lock(s.mu);
        if (handle <= 0 || handle > static_cast<int>(s.luts.size())) return nullptr;
        return s.luts[static_cast<size_t>(handle - 1)].get();
    }

private:
    struct State {
        std::mutex mu;
        std::map<std::string, int> byPath;
        std::vector<std::unique_ptr<rawedit::Lut>> luts;
    };
    static State& state() {
        static State s;
        return s;
    }
};

}  // namespace gles
