#include "../app/src/main/cpp/math.hpp"
#include <cassert>
#include <random>
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
 std::mt19937 generator(42);std::uniform_real_distribution<float> angle(-3.f,3.f);
 for(int i=0;i<1000;i++){
   float t=angle(generator);float rotation[]={0,std::sin(t/2),0,std::cos(t/2)};
   auto m=Mat4::quaternion(rotation);m.m[12]=angle(generator);m.m[13]=angle(generator);m.m[14]=angle(generator);
   auto identity=m*m.inverse();for(int j=0;j<16;j++)assert(std::abs(identity.m[j]-(j%5==0?1.f:0.f))<.00001f);
 }
 assert(panelUv({0,0,0},Vec3{1,0,-2}.normalized(),false,2,u,v));assert(std::abs(u-1)<.0001f);
 assert(!panelUv({0,0,0},Vec3{2,0,-2}.normalized(),false,2,u,v));
 assert(panelUv({0,0,0},Vec3{2*std::sin(.4f),0,-2*std::cos(.4f)}.normalized(),true,2,u,v));assert(std::abs(u-.9f)<.0001f);
}
