#include "../render/include/trackmr/math.hpp"
#include <cassert>
int main(){
 for(float radius:{0.f,1.8f})for(float width:{.5f,2.15f,2.8f})for(float u:{.05f,.25f,.5f,.75f,.95f})for(float v:{.1f,.5f,.9f}){
  float x=(u-.5f)*width,z=0;
  if(radius>0){float a=x/radius;x=radius*std::sin(a);z=radius*(1-std::cos(a));}
  mr::Vec3 origin{.1f,.1f,2},point{x,(.5f-v)*1.4f,z};
  float t,actualU,actualV;
  assert(mr::curvedRectHit(origin,(point-origin).normalized(),width,1.4f,radius,t,actualU,actualV));
  assert(std::abs(actualU-u)<.0001f&&std::abs(actualV-v)<.0001f);
 }
 float t,u,v;assert(!mr::curvedRectHit({0,0,2},{0,0,1},2,1,1.8f,t,u,v));
 assert(!mr::curvedRectHit({4,0,2},{0,0,-1},2,1,1.8f,t,u,v));
}
