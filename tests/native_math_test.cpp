#include "../app/src/main/cpp/math.hpp"
#include <cassert>
int main(){
 using namespace mr;
 Mat4 a=Mat4::identity();a.m[12]=2;a.m[13]=3;a.m[14]=-4;
 Mat4 id=a*a.inverse();for(int i=0;i<16;i++)assert(std::abs(id.m[i]-(i%5==0?1.f:0.f))<.0001f);
 float q[]={0,.70710678f,0,.70710678f};Mat4 r=Mat4::quaternion(q);id=r*r.inverse();assert(std::abs(id.m[0]-1)<.0001f);
 float u,v;assert(panelUv({0,0,0},{0,0,-1},true,2.4,u,v));assert(std::abs(u-.5)<1e-6&&std::abs(v-.5)<1e-6);
 assert(!panelUv({0,0,0},{0,0,1},true,2.4,u,v));
 assert(!panelUv({0,0,0},{0,1,0},false,2.4,u,v));
 assert(sphereHit({0,0,0},{0,0,-1},{0,0,-2},.2f)>1.7f);
 assert(sphereHit({0,0,0},{0,0,1},{0,0,-2},.2f)<0);
}
