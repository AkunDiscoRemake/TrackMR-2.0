#include "../openxr/src/main/cpp/frame_protocol.hpp"
#include <cassert>
int main(){
 FrameProtocol p;
 bool rejected=false;try{p.begun();}catch(const std::logic_error&){rejected=true;}assert(rejected);
 p.waited();p.begun(); // shouldRender=false is a legal empty submission
 p.ended();
 for(int i=0;i<1000;i++){p.waited();p.begun();for(int eye=0;eye<2;eye++){p.acquired();p.imageReady();p.released();}p.ended();}
 p.waited();p.begun();p.acquired();rejected=false;try{p.released();}catch(const std::logic_error&){rejected=true;}assert(rejected);
 p.aborted();p.waited();p.begun();p.ended();
}
