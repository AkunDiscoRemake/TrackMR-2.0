#pragma once
#include <stdexcept>
// Executable order checks for the client, not a simulated XR runtime.
class FrameProtocol {
    enum class State { Idle, Waited, Begun, Acquired, ImageReady };
    State state=State::Idle;
    void require(State expected){if(state!=expected)throw std::logic_error("Invalid OpenXR frame transaction order");}
public:
    void waited(){require(State::Idle);state=State::Waited;}
    void begun(){require(State::Waited);state=State::Begun;}
    void acquired(){require(State::Begun);state=State::Acquired;}
    void imageReady(){require(State::Acquired);state=State::ImageReady;}
    void released(){require(State::ImageReady);state=State::Begun;}
    void ended(){require(State::Begun);state=State::Idle;}
    void aborted(){state=State::Idle;}
};
